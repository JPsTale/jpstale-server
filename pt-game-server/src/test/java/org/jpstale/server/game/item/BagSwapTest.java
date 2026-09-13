package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：**换手**（`ItemService.swapWithHand`）—— 手上那件 ↔ 背包里某件，**原子互换**。
 *
 * 为什么必须是原子的（用户 2026-09-14 实测两件叠在同一格）：鼠标位只有一个 ——
 * 拿着 A 时 `takeToHand(B)` 必然 `handBusy`；而"先把 A 放下"又要求 B 让开那一格，
 * 拆成两步在任何顺序下都会撞死。原版 `ChangeInvenItem` 的换手本来就是一次做完的。
 * 服务端用 `writeMoved`（两行互换：先停车再落地）保证唯一键不撞。
 */
public class BagSwapTest {

    private static ItemList item(int id, int w, int h) {
        ItemList t = new ItemList();
        t.setId(id);
        t.setName("it" + id);
        t.setClassItem(4);
        t.setWidth(w);
        t.setHeight(h);
        t.setWeight(1);
        return t;
    }

    private static ItemStorageService fakeStorage() {
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> {
            String n = method.getName();
            if (n.equals("update") || n.equals("insert") || n.equals("updateById") || n.equals("restore")) {
                return 1;
            }
            return method.getDefaultValue();
        };
        ItemMapper mapper = (ItemMapper) Proxy.newProxyInstance(
                ItemMapper.class.getClassLoader(), new Class<?>[]{ItemMapper.class}, h);
        return new ItemStorageService(mapper);
    }

    private final ItemService service = new ItemService(new ItemRollService(null), fakeStorage(),
            new PlayerStatCalculator());

    private static Player newPlayer(PlayerItems items) {
        Player p = new Player(null, 1);
        p.setItems(items);
        p.setCharacterId(1L);
        p.setName("tester");
        p.setStrength(50);
        return p;
    }

    private static ItemInstance inst(long uid, int loc, int slot, ItemList def) {
        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setCharacterId(1);
        it.setLocation(loc);
        it.setSlot(slot);
        it.setTemplate(def);
        it.setItemListId(Math.toIntExact(def.getId()));
        it.setCount(1);
        return it;
    }

    @Test
    public void swapMovesHandIntoCellAndTargetIntoHand() {
        ItemList sword = item(80, 44, 44);      // 2×2 格
        ItemList shield = item(409, 44, 44);    // 2×2 格（同尺寸 → 互换不需要额外空位）
        PlayerItems items = new PlayerItems();
        ItemInstance hand = inst(1L, ItemLocations.EQUIP, ItemLocations.HELD_SLOT, sword);
        int slot = 2 * ItemLocations.BAG_W + 3; // 背包 (x=3,y=2)
        ItemInstance target = inst(2L, ItemLocations.BAG_PAGE, slot, shield);
        items.index(hand);
        items.index(target);
        Player p = newPlayer(items);

        ItemService.OpResult r = service.swapWithHand(p, 1L, 2L);

        assertEquals("换手成功", ItemService.OpReason.OK, r.reason);
        assertTrue("被撞件换到鼠标位", ItemLocations.isHeld(items.byUid(2L)));
        assertEquals("手上那件落到被撞件的原格", slot, items.byUid(1L).getSlot());
        assertEquals("且确实在背包画布上", ItemLocations.BAG_PAGE, items.byUid(1L).getLocation());
        // 返回的实例 = 换到手上那件（客户端据此把图标挂上）
        assertEquals(2L, (long) r.instance.getId());
        // 位图归属正确（不会两件都"看不见"）
        CanvasGrid cg = items.canvas(ItemLocations.BAG_PAGE);
        assertTrue("目标格已归手上那件", cg.xOf(slot) == 3 && cg.yOf(slot) == 2);
    }

    @Test
    public void rejectsWhenHandIsNotAtMouseSlot() {
        ItemList sword = item(80, 44, 44);
        ItemList shield = item(409, 44, 44);
        PlayerItems items = new PlayerItems();
        // "hand" 其实还在背包里（客户端乱报 uid）→ 必须拒绝，否则等于凭空换位
        ItemInstance notHeld = inst(1L, ItemLocations.BAG_PAGE, 0, sword);
        ItemInstance target = inst(2L, ItemLocations.BAG_PAGE, 2, shield);
        items.index(notHeld);
        items.index(target);
        Player p = newPlayer(items);

        assertEquals(ItemService.OpReason.NOT_IN_BAG, service.swapWithHand(p, 1L, 2L).reason);
        assertEquals("原样不动", 0, items.byUid(1L).getSlot());
        assertEquals(2, items.byUid(2L).getSlot());
    }

    @Test
    public void rejectsWhenHandTooBigForTargetCell() {
        ItemList big = item(80, 88, 88);        // 4×4
        ItemList small = item(409, 22, 22);     // 1×1
        PlayerItems items = new PlayerItems();
        ItemInstance hand = inst(1L, ItemLocations.EQUIP, ItemLocations.HELD_SLOT, big);
        // 目标在背包最后一格：右边/下边容不下 4×4
        int slot = (ItemLocations.BAG_H - 1) * ItemLocations.BAG_W + (ItemLocations.BAG_W - 1);
        ItemInstance target = inst(2L, ItemLocations.BAG_PAGE, slot, small);
        items.index(hand);
        items.index(target);
        Player p = newPlayer(items);

        ItemService.OpReason reason = service.swapWithHand(p, 1L, 2L).reason;
        assertNotEquals("容不下就拒绝（不是硬塞）", ItemService.OpReason.OK, reason);
        assertTrue("手上那件仍在鼠标位", ItemLocations.isHeld(items.byUid(1L)));
        assertEquals("被撞件仍在原格", slot, items.byUid(2L).getSlot());
    }
}
