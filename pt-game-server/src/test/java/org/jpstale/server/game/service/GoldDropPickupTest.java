package org.jpstale.server.game.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.entity.GroundItem;
import org.jpstale.server.game.item.GroundItemManager;
import org.jpstale.server.game.item.ItemInstance;
import org.jpstale.server.game.item.ItemRules;
import org.jpstale.server.game.model.Player;
import org.junit.Test;

/**
 * L0：**金币掉落物 → 拾取入账（含等级上限）**。
 *
 * 依据：
 * - 金币是**掉在地上的道具**（原版 `itemlist` 的 Gold 行 `idcode = 0x05010000 = sinGG1|sin01`），
 *   拾取时 `sinPlusMoney` + `SIN_SOUND_COIN` 并**直接 return**（`sinInvenTory.cpp:7808`）——
 *   不入背包、不占格、不负重；超上限则**留在地上**、不发放、不截断。
 * - 判据是"**家族 + 带金额**"两个条件（用户 2026-09-14 指出），只看一半都会误判。
 * - 上限公式照抄 `cINVENTORY::CheckMoneyLimit`（`sinInvenTory.cpp:6473-6506`），相等允许。
 */
public class GoldDropPickupTest {

    /** 现成的 mapnpc/itemlist 都无关；这里只测纯逻辑 + 地面物管线。 */
    private static ItemInstance goldItem(int money) {
        ItemList t = new ItemList();
        t.setId(484);
        t.setName("Gold");
        t.setIdCode(ItemRules.CODE_GOLD);      // 0x05010000
        t.setCodeImg1("GG101");                // 没有对应模型
        t.setCodeImg2("DRCOIN");               // 真正的掉落模型（资产 itdrcoin.smd）
        t.setWidth(22);
        t.setHeight(22);
        t.setWeight(1);
        ItemInstance it = new ItemInstance();
        it.setId(1L);
        it.setCharacterId(1);
        it.setTemplate(t);
        it.setItemListId(484);
        it.setItemCode(ItemRules.CODE_GOLD);
        it.setCount(1);
        it.setLocation(org.jpstale.server.game.item.ItemLocations.BAG_PAGE);
        it.setSlot(0);
        return it;
    }

    // ---------------- 上限公式（原版 CheckMoneyLimit） ----------------

    @Test
    public void goldLimitMatchesOriginalFormula() {
        assertEquals("≤10 级：20 万", 200_000L, GoldService.goldLimit(1, 0));
        assertEquals(200_000L, GoldService.goldLimit(10, 0));
        assertEquals("11 级起走 L*200000-1800000", 400_000L, GoldService.goldLimit(11, 0));
        assertEquals(2_200_000L, GoldService.goldLimit(20, 0));
        assertEquals(6_200_000L, GoldService.goldLimit(40, 0));
        assertEquals(18_200_000L, GoldService.goldLimit(100, 0));
        assertEquals("转职档位（我们暂无该列，先钉住公式）", 10_000_000L, GoldService.goldLimit(40, 1));
        assertEquals(50_000_000L, GoldService.goldLimit(40, 2));
        assertEquals(GoldService.MAX_MONEY, GoldService.goldLimit(40, 3));
    }

    // ---------------- 判据：家族 + 金额，缺一不可 ----------------

    @Test
    public void goldDropRequiresBothFamilyAndMoney() {
        assertTrue("金币家族 + 带金额 = 金币掉落物",
            ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 123));
        assertTrue("家族命中即可（金额由第二个条件把住）",
            ItemRules.isGoldFamily(ItemRules.CODE_GOLD));
        assertFalse("★ 金币家族但 money=0 → 不是金币（按普通物品走）",
            ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 0));
        assertFalse("★ 非金币家族即使带金额 → 也不是金币",
            ItemRules.isGoldDrop(0x01010000 /* WA101 */, 123));
        assertFalse("空码不算", ItemRules.isGoldDrop(0, 999));
    }

    // ---------------- 地面物承载金额 ----------------

    @Test
    public void groundItemCarriesMoneyAndIsSquashable() {
        GroundItemManager m = new GroundItemManager();
        GroundItem gi = m.add(goldItem(0), 1, 10, 0, 10, 7L, 0, 500);
        assertNotNull(gi);
        assertEquals("金额挂在**地面物**上（地面物是内存对象，不进 DB）", 500, gi.money);
        assertEquals("金币是可被覆盖的挤压级（Level 0，原版 sinGG1 语义）", 0, gi.level);
        assertEquals("归属者仅本人可见（dropispublic=0 时）", 7L, gi.ownerId);

        GroundItem plain = m.add(goldItem(0), 1, 20, 0, 20, 0L, 0);
        assertEquals("普通投放不带金额", 0, plain.money);
    }

    @Test
    public void expiredGoldIsSwept() {
        GroundItemManager m = new GroundItemManager();
        GroundItem gi = m.add(goldItem(0), 1, 10, 0, 10, 0L, 1, 100);   // 1ms TTL
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertNull("过期后取不到（惰性清扫）", m.byId(1, gi.getId()));
    }

    // ---------------- 入账原语：上限不截断 ----------------

    /** 只需 persistStats 不炸；推送路径在无会话时不走（沿用既有约定）。 */
    private static PlayerService stubPlayerService() {
        return new PlayerService() {
            @Override
            public void persistStats(Player player) {
                // no-op：本测试不碰 DB
            }
        };
    }

    private static Player player(int level, int gold) {
        Player p = new Player(null, 1);
        p.setName("tester");
        p.setLevel(level);
        p.setGold(gold);
        return p;
    }

    @Test
    public void creditRespectsLevelLimitAndNeverTruncates() {
        GoldService gs = new GoldService(stubPlayerService());

        Player p = player(10, 199_999);
        assertEquals("差 1 元到上限仍可入账（原版用 <=）", GoldService.Result.OK, gs.add(null, p, 1, "test"));
        assertEquals(200_000, p.getGold());

        assertEquals("再入账 1 元就超限 → 拒绝", GoldService.Result.OVER_LIMIT, gs.add(null, p, 1, "test"));
        assertEquals("★ 被拒时金币**一点不动**（不截断）", 200_000, p.getGold());

        Player p20 = player(20, 2_199_000);
        assertEquals(2_200_000L, GoldService.goldLimitOf(p20));
        assertEquals(GoldService.Result.OK, gs.add(null, p20, 1_000, "test"));
        assertEquals(2_200_000, p20.getGold());
        assertEquals(GoldService.Result.OVER_LIMIT, gs.add(null, p20, 1, "test"));
    }

    @Test
    public void debitRequiresEnoughGold() {
        GoldService gs = new GoldService(stubPlayerService());
        Player p = player(40, 100);

        assertEquals("余额不足 → 拒绝", GoldService.Result.INSUFFICIENT, gs.add(null, p, -101, "shop"));
        assertEquals(100, p.getGold());
        assertEquals(GoldService.Result.OK, gs.add(null, p, -100, "shop"));
        assertEquals(0, p.getGold());
    }
}
