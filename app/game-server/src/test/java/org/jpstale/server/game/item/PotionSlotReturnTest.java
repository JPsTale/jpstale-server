package org.jpstale.server.game.item;

import static org.jpstale.server.game.item.ItemTestSupport.inBag;
import static org.jpstale.server.game.item.ItemTestSupport.inSlot;
import static org.jpstale.server.game.item.ItemTestSupport.newPlayer;
import static org.jpstale.server.game.item.ItemTestSupport.newService;
import static org.jpstale.server.game.item.ItemTestSupport.potion;
import static org.jpstale.server.game.item.ItemTestSupport.stack;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.model.Player;
import org.junit.Test;

/**
 * L0：**从药水槽拿起再原地放回，手必须真的空出来**（用户 2026-09-14 实测的复现）。
 *
 * 现象：拿起药水槽的药水 → 原地放回 → 之后去拿装备槽的装备，全被拒（"鼠标位已被占用"）。
 * 根因：`putPotionToSlot` 的「整堆搬入」分支写死 `takeFromCanvas(BAG, ...)` 摘件，
 * 而来源是**鼠标位**（装备栏 slot=-1）⇒ `(EQUIP,-1)` 那条索引从未释放 ⇒ 同一件同时挂在两格。
 *
 * 服务端日志的形状（放进测试断言里做对照）：
 * <pre>
 *   [TakeToHand] 拿起 uid=243 Mystic Life Potion ← location=0 slot=12
 *   [Potion] 放入药水槽2: Mystic Life Potion x1 -&gt; 槽内 1/40
 *   [TakeToHand] uid=251 拒绝：鼠标位已被占用      ← 从此再也拿不起任何东西
 * </pre>
 */
public class PotionSlotReturnTest {

    private final ItemService service = newService();

    @Test
    public void puttingPotionBackIntoItsSlotReleasesTheHand() {
        ItemList life = potion(426, "Mystic Life Potion", 40);   // 槽容量 40（用户日志里的 1/40）
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        ItemInstance potion = inSlot(items, 12, life, 1);

        // ① 拿起（药水槽 12 → 鼠标位 = 装备栏 slot=-1）
        assertEquals("拿起成功", ItemService.OpReason.OK, service.takeToHand(p, potion.getId()).reason);
        assertEquals("药水已在鼠标位", potion.getId(),
                Long.valueOf(items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getId()));

        // ② 原地放回（源 = 鼠标位，目标 = 药水槽 12；槽是空的 → 整堆搬入分支）
        assertEquals("放回成功", ItemService.OpReason.OK, service.putPotionToSlot(p, potion.getId(), 12).reason);

        // ③ 断言：药水回到槽里，且**鼠标位空出来**
        assertEquals("药水回到槽 12", potion.getId(), Long.valueOf(items.at(ItemLocations.EQUIP, 12).getId()));
        assertNull("★ 鼠标位必须空（原来这里仍挂着同一件 → 手被永久占用）",
                items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT));

        // ④ 断言：拿着装备槽的装备要能成功（用户报的"拿不起来"）
        ItemInstance weapon = inBag(items, 251L, 8, life, 1);
        assertEquals("之后拿起别的道具不再被拒", ItemService.OpReason.OK,
                service.takeToHand(p, weapon.getId()).reason);
        assertEquals("新手持 = 刚拿起的那件", weapon.getId(),
                Long.valueOf(items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getId()));
    }

    @Test
    public void mergingIntoOccupiedSlotAlsoReleasesTheHand() {
        ItemList life = potion(426, "Mystic Life Potion", 40);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        inSlot(items, 12, life, 5);                       // 槽里已有同种 5
        ItemInstance potion = inBag(items, 243L, 3, life, 1);

        assertEquals(ItemService.OpReason.OK, service.takeToHand(p, potion.getId()).reason);
        assertEquals(ItemService.OpReason.OK, service.putPotionToSlot(p, potion.getId(), 12).reason);

        assertEquals("槽内合并为 6", 6, items.at(ItemLocations.EQUIP, 12).getCount());
        assertNull("★ 合并后鼠标位同样要空", items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT));
        assertNull("源已不在索引里", items.byUid(potion.getId()));
    }

    @Test
    public void partialPourKeepsRemainderInHand() {
        ItemList life = potion(426, "Mystic Life Potion", 2);   // 容量 2
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        ItemInstance potion = inBag(items, 243L, 3, life, 5);

        assertEquals(ItemService.OpReason.OK, service.takeToHand(p, potion.getId()).reason);
        assertEquals(ItemService.OpReason.OK, service.putPotionToSlot(p, potion.getId(), 12).reason);

        assertEquals("槽收下 2 瓶", 2, items.at(ItemLocations.EQUIP, 12).getCount());
        assertEquals("★ 余数 3 瓶留在手上（有意行为：搬不完留在原处）",
                3, items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getCount());
    }

    /**
     * **异种药水 = 交换**（原版 `MouseSetPotion` 的异种分支）。用户 2026-09-14 报障：
     * "我要把中级药水放进已经有高级药水的槽位，等于说是背包里的交换位置，但被拒绝"。
     * 原版：槽内那叠 `memcpy(pItem, &TempItem)` 回**鼠标位**，手上那叠灌进槽（上限 Potion_Space）。
     */
    @Test
    public void differentPotionSwapsSlotStackToHand() {
        ItemList high = potion(428, "Mystic Life Potion (High)", 40);
        ItemList mid = potion(430, "Mystic Life Potion (Mid)", 40);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        ItemInstance inSlotPotion = inSlot(items, 12, high, 2);      // 槽里已有高级药水 2 瓶
        ItemInstance held = inBag(items, 253L, 4, mid, 1);           // 手上拿着中级药水 1 瓶

        assertEquals(ItemService.OpReason.OK, service.takeToHand(p, held.getId()).reason);
        ItemService.OpResult r = service.putPotionToSlot(p, held.getId(), 12);

        assertEquals("异种不再拒绝，而是交换", ItemService.OpReason.OK, r.reason);
        assertEquals("槽里现在是中级药水", held.getId(), Long.valueOf(items.at(ItemLocations.EQUIP, 12).getId()));
        assertEquals("★ 被换下的高级药水回到鼠标位（不是掉进背包）",
                inSlotPotion.getId(), Long.valueOf(items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getId()));
        assertEquals("高级药水数量原样带过来", 2,
                items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getCount());
        assertTrue("被换下的那叠在 displaced 里（handler 据此推给客户端）",
                r.displaced.stream().anyMatch(d -> d.getId().equals(inSlotPotion.getId())));
        assertTrue("手上不再有别人的影子", items.at(ItemLocations.BAG, 4) == null);
    }

    /**
     * 手上那叠**超过槽容量** ⇒ 只灌 `cap` 瓶进槽，**余数进背包**（原版 `TempPotionItem` + `InvenEmptyAearCheck`），
     * 被换下的那叠仍然回鼠标位。
     */
    @Test
    public void oversizedHandStackOverflowsToBagOnSwap() {
        ItemList high = potion(428, "High", 40);
        ItemList mid = potion(430, "Mid", 2);                        // 容量只有 2
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        ItemInstance inSlotPotion = inSlot(items, 12, high, 3);
        ItemInstance held = inBag(items, 248L, 3, mid, 5);           // 手上 5 瓶，槽只装 2

        assertEquals(ItemService.OpReason.OK, service.takeToHand(p, held.getId()).reason);
        assertEquals(ItemService.OpReason.OK, service.putPotionToSlot(p, held.getId(), 12).reason);

        assertEquals("槽收下 2 瓶", 2, items.at(ItemLocations.EQUIP, 12).getCount());
        assertEquals("槽里是手上那种", held.getId(), Long.valueOf(items.at(ItemLocations.EQUIP, 12).getId()));
        assertEquals("被换下的高级药水回鼠标位", inSlotPotion.getId(),
                Long.valueOf(items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getId()));
        ItemInstance rest = items.itemsIn(ItemLocations.BAG).stream()
                .filter(x -> x.getItemListId() != null && x.getItemListId() == 430).findFirst().orElse(null);
        assertTrue("余下 3 瓶进了背包（新行）", rest != null && rest.getCount() == 3);
    }

    /** 余数放不下背包 ⇒ **整件事放弃**，一行都不动（原版 `return FALSE` 的等价物）。 */
    @Test
    public void swapIsAbortedWhenRemainderHasNoBagSpace() {
        ItemList high = potion(428, "High", 40);
        ItemList mid = potion(430, "Mid", 2);
        ItemList filler = potion(426, "Filler", 1);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        ItemInstance inSlotPotion = inSlot(items, 12, high, 3);
        ItemInstance held = inBag(items, 248L, 3, mid, 5);
        assertEquals(ItemService.OpReason.OK, service.takeToHand(p, held.getId()).reason);
        // 把背包**每一格**都塞满（12×12 全 1×1；手上那件已不在背包，所以包括它原来的格子）
        long uid = 2000L;
        for (int c = 0; c < 144; c++) {
            inBag(items, uid++, c, filler, 1);
        }
        assertEquals("前置条件：背包确已无空位", -1, items.canvas(ItemLocations.BAG).findFreeSlot(1, 1));
        ItemService.OpResult r = service.putPotionToSlot(p, held.getId(), 12);

        assertEquals("拿不到背包空位 → 整件事放弃", ItemService.OpReason.BAG_FULL, r.reason);
        assertEquals("★ 槽里仍是原来那叠（未被清空）", inSlotPotion.getId(),
                Long.valueOf(items.at(ItemLocations.EQUIP, 12).getId()));
        assertEquals("★ 手上仍是原来那叠（未被换走）", held.getId(),
                Long.valueOf(items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getId()));
    }
    /**
     * 用户 2026-09-14 实测："9 瓶低级药水（容量 2）放进**空**药水槽 → 期望进 2 瓶、余 7 瓶回背包原位；
     * 实际根本放不进去。"
     *
     * 这条路走的是 `putPotionToSlot` 的**拆堆**分支（`n < 源堆数量`），而之前验证过的两种情况
     * （同种合并 / 整堆搬入）都不经过它 —— 所以这条用例专门钉它。
     */
    @Test
    public void nineStackIntoEmptySlotPutsTwoAndKeepsSevenOnHand() {
        ItemList life = potion(426, "Mystic Life Potion", 2);      // 容量 2
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        ItemInstance stack = inBag(items, 900L, 3, life, 9);

        assertEquals(ItemService.OpReason.OK, service.takeToHand(p, stack.getId()).reason);
        ItemService.OpResult r = service.putPotionToSlot(p, stack.getId(), 12);

        assertEquals("应当成功（放得下 2 瓶就不该整笔拒绝）", ItemService.OpReason.OK, r.reason);
        assertEquals("槽里 2 瓶", 2, items.at(ItemLocations.EQUIP, 12).getCount());
        assertEquals("★ 手上余 7 瓶", 7, items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getCount());
        assertEquals("★ 余数仍在**原来那件**上（拆堆只扣减，不搬走源）", 900L,
                items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT).getId().longValue());
    }
    /**
     * **发放时余数不许丢**（用户 2026-09-14 批准修）。`grantInstanceToBag` 过去在并堆时写成
     * `min(数量, 1000-已有)` 后**直接 return** —— 已有堆 998/1000 时买 4 瓶只进 2 瓶、钱照扣 4 瓶。
     * 现在：先算计划 → **先确认余数有格子**（拿不到就整笔放弃、一行不动）→ 再并入 + 落余数。
     */
    @Test
    public void grantMergeKeepsRemainderInNewSlot() {
        ItemList life = potion(426, "Mystic Life Potion", 2);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        p.setStrength(5000);   // 目的不是测负重：999 瓶药水本身就会压过普通负重上限
        ItemInstance existing = inBag(items, 700L, 0, life, 999);       // 已有堆几乎满
        ItemInstance fresh = stack(701L, life, 4);                      // 再发 4 瓶

        ItemService.GrantResult r = service.grantInstanceToBag(p, fresh);

        assertEquals("发放成功", ItemService.GrantReason.OK, r.reason);
        assertEquals("★ 已有堆补满到 1000（不是把 4 瓶全丢）", 1000, existing.getCount());
        int inNewRow = items.itemsIn(ItemLocations.BAG_PAGE).stream()
                .filter(x -> x.getId() != null && x.getId() == 701L).mapToInt(ItemInstance::getCount).sum();
        assertEquals("★ 余 3 瓶落在新行（总数守恒）", 3, inNewRow);
        assertEquals("★ touched 含两行，调用方据此全推", 2, r.touched.size());
    }

    /** 背包真满、余数无处落 ⇒ **整笔放弃**（一行不动），不能"合并一半、丢一半"。 */
    @Test
    public void grantIsAbortedWhenRemainderHasNoSlot() {
        ItemList life = potion(426, "Mystic Life Potion", 2);
        ItemList filler = potion(430, "Filler", 1);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);
        p.setStrength(5000);   // 同上：先排除负重干扰，专测"余数无处落"
        ItemInstance existing = inBag(items, 700L, 0, life, 999);
        long uid = 2000L;
        for (int c = 1; c < 144; c++) {
            inBag(items, uid++, c, filler, 1);
        }
        ItemInstance fresh = stack(701L, life, 4);

        ItemService.GrantResult r = service.grantInstanceToBag(p, fresh);

        assertEquals("拿不到新格 → 整笔放弃", ItemService.GrantReason.BAG_FULL, r.reason);
        assertEquals("★ 已有堆一点没动（不是先合并 1 瓶再失败）", 999, existing.getCount());
    }
}