package org.jpstale.server.game.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.dao.gamedb.mapper.NpcListMapper;
import org.junit.Test;

/**
 * L0：NPC 商店的**数据解析与定价**（不碰 DB、不碰会话）。
 *
 * 依据：
 * - 商品清单 = `npclist` 的 `weaponshop/defenseshop/miscshop`（空格分隔的 `codeimg1`）；
 * - **同码物品**里有一件是 40 级转职任务武器（外观与 37 级那把相同）⇒ 判据是**取 questid 为空的那件**，
 *   只有任务行则**不上架**（用户 2026-09-14 说明）；
 * - 卖出价 = EU `ItemServer::GetItemSellPrice`（`itemserver.cpp:4066-4095`）：
 *   `min(price/5, round(price*(dur/max) + (price - price*(dur/max))*0.25)/5)`。
 */
public class NpcShopTest {

    private static ItemList item(int id, String code, String name, int price, Integer questId) {
        ItemList t = new ItemList();
        t.setId(id);
        t.setCodeImg1(code);
        t.setName(name);
        t.setPrice(price);
        t.setQuestId(questId);
        return t;
    }

    private static NpcList npc(int id, String name, String weapon, String defense, String misc) {
        NpcList n = new NpcList();
        n.setId(id);
        n.setName(name);
        n.setWeaponShop(weapon);
        n.setDefenseShop(defense);
        n.setMiscShop(misc);
        return n;
    }

    private static <T> T stub(Class<T> iface) {
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> method.getDefaultValue();
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h));
    }

    /** `NpcSpawnService` 只被 `findInstance`（按实体 id 直查）用到（本测试不覆盖它，交给处理器路径），故传 null。 */
    private static NpcShopService service() {
        return new NpcShopService(stub(NpcListMapper.class), stub(ItemListMapper.class), null);
    }

    // ---------------- 是否是商家 ----------------

    @Test
    public void merchantIsWhicheverShopColumnIsNotBlank() {
        NpcShopService s = service();
        assertTrue(s.isMerchant(npc(2, "lynn", null, null, "pl101")));
        assertTrue(s.isMerchant(npc(63, "umph", "ws108", "da107", null)));
        assertFalse("三个列都空 = 不是商家", s.isMerchant(npc(1, "keeper", null, null, null)));
        assertFalse("未知 npcId", s.isMerchant(999L));
    }

    // ---------------- 同码判据（转职任务武器绝不上架） ----------------

    @Test
    public void duplicateCodePrefersNonQuestRow() {
        NpcShopService s = service();
        // 37 级武器（可卖）与 40 级转职任务武器（同码、同外观）——真实数据的形状
        s.primeForTest(Map.of(), Map.of("wn109", List.of(
                item(500, "wn109", "Tatanka Phantom", 22_000, null),        // 37 级
                item(501, "wn109", "Dark Skull", 22000, 40))));            // 转职任务武器
        ItemList pick = s.resolveCode("wn109");
        assertEquals("★ 取非任务那件", 500L, pick.getId().longValue());
    }

    @Test
    public void questOnlyCodeIsNotListed() {
        NpcShopService s = service();
        s.primeForTest(Map.of(), Map.of("qt116", List.of(item(600, "qt116", "Quest Blade", 0, 12))));
        assertNull("★ 只有任务行 ⇒ 不上架", s.resolveCode("qt116"));
    }

    @Test
    public void multipleNormalRowsPickLowestIdWithError() {
        NpcShopService s = service();
        s.primeForTest(Map.of(), Map.of("wa108", List.of(
                item(700, "wa108", "B", 10, null),
                item(701, "wa108", "A", 10, null))));
        assertEquals("同码多个非任务行 → 取最小 id（并 log.error）", 700L, s.resolveCode("wa108").getId().longValue());
    }

    @Test
    public void unknownCodeIsNull() {
        assertNull(service().resolveCode("nope999"));
    }

    // ---------------- 清单解析（含 kind 与价格） ----------------

    @Test
    public void offersCarryKindAndPrice() {
        NpcShopService s = service();
        ItemList potion = item(427, "pl101", "Mini Life Potion", 35, null);
        ItemList sword = item(80, "ws108", "Great Sword", 6200, null);
        s.primeForTest(
                Map.of(63L, npc(63, "umph", "ws108", null, "pl101")),
                Map.of("ws108", List.of(sword), "pl101", List.of(potion)));

        List<NpcShopService.Offer> offers = s.offers(63);
        assertEquals("两个类别合并下发，行上带 kind", 2, offers.size());
        NpcShopService.Offer weapon = offers.stream().filter(o -> o.kind() == NpcShopService.KIND_WEAPON).findFirst().orElseThrow();
        NpcShopService.Offer misc = offers.stream().filter(o -> o.kind() == NpcShopService.KIND_MISC).findFirst().orElseThrow();
        assertEquals(80, weapon.itemlistId());
        assertEquals(6200L, weapon.price());
        assertEquals(35L, misc.price());
    }

    // ---------------- 卖出价（EU GetItemSellPrice） ----------------

    @Test
    public void sellPriceIsAtMostOneFifth() {
        assertEquals("满耐久 = 原价 /5", 200L, NpcShopService.sellPrice(1000, 100, 100));
        assertEquals("★ 无耐久物品（dur=maxDur=0）→ 满价 /5", 200L, NpcShopService.sellPrice(1000, 0, 0));
        assertEquals("耐久字段有值但为 0（损坏）→ 按 1 点算：round(10+990*0.25)/5 = 51",
                51L, NpcShopService.sellPrice(1000, 0, 100));
        assertEquals("最大值就是 price/5", 200L, NpcShopService.sellPrice(1000, 1000, 100));
        assertEquals("价格为 0 → 0", 0L, NpcShopService.sellPrice(0, 10, 10));
    }

    @Test
    public void wornDurabilityDiscountsToQuarterWeight() {
        // 半耐久：round(1000*0.5 + (1000-500)*0.25) = 625 → /5 = 125
        assertEquals(125L, NpcShopService.sellPrice(1000, 50, 100));
        // 一成耐久：round(100 + 900*0.25) = 325 → /5 = 65
        assertEquals(65L, NpcShopService.sellPrice(1000, 10, 100));
    }

    /** 买入价：我们没有稀有度数据，直接就是 itemlist.price（差异写在 NpcShopService 的类注释里）。 */
    @Test
    public void buyPriceIsListPrice() {
        assertEquals(6200L, NpcShopService.buyPrice(item(80, "ws108", "Great Sword", 6200, null)));
        assertEquals(0L, NpcShopService.buyPrice(null));
    }
}
