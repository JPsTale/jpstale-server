package org.jpstale.server.game.item;

import static org.jpstale.server.game.item.ItemTestSupport.inBag;
import static org.jpstale.server.game.item.ItemTestSupport.inSlot;
import static org.jpstale.server.game.item.ItemTestSupport.newPlayer;
import static org.jpstale.server.game.item.ItemTestSupport.newService;
import static org.jpstale.server.game.item.ItemTestSupport.potion;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

}
