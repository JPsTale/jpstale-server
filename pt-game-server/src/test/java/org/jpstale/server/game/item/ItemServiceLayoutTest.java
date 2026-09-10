package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：BagLayout 全量+seq 语义与跨容器规则（test-背包装具系统 §6.6/§C LAY-001..008）。
 * 用 JDK 动态代理做 ItemMapper 轻量 double（updateById 吞掉写），验证纯内存布局路径。
 */
public class ItemServiceLayoutTest {

    /** ItemMapper 动态代理：仅 updateById/insert/update 返回空实现，其余默认。 */
    private ItemStorageService fakeStorage() {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            String name = method.getName();
            if (name.equals("updateById") || name.equals("insert") || name.equals("update")) {
                return switch (name) {
                    case "insert" -> 1;
                    default -> 1;
                };
            }
            return method.getDefaultValue();
        };
        ItemMapper mapper = (ItemMapper) Proxy.newProxyInstance(
            ItemMapper.class.getClassLoader(), new Class<?>[]{ItemMapper.class}, handler);
        return new ItemStorageService(mapper);
    }

    private final ItemService service = new ItemService(null, fakeStorage(),
            new PlayerStatCalculator());

    private static ItemList mat128() { // 2×1 材料（宽高 44×22 → gridW=2, gridH=1）
        ItemList t = new ItemList();
        t.setClassItem(0);
        t.setWidth(44);
        t.setHeight(22);
        t.setWeight(1);
        return t;
    }

    private static ItemList potion() {
        ItemList t = new ItemList();
        t.setClassItem(8192);
        t.setWidth(22);
        t.setHeight(22);
        t.setWeight(0);
        return t;
    }

    private static ItemInstance index(PlayerItems items, long uid, int location, int slot, ItemList template) {
        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setLocation(location);
        it.setSlot(slot);
        it.setTemplate(template);
        it.setItemListId((int) uid);
        it.setCount(1);
        items.index(it);
        return it;
    }

    private static Player newPlayer(PlayerItems items, String name) {
        Player p = new Player(null, 0);
        p.setItems(items);
        p.setName(name);
        return p;
    }

    private PlayerItems freshItems() {
        return new PlayerItems();
    }

    private record E(Long uid, int loc, int slot) implements ItemService.BagLayoutEntry {
        @Override public Long uid() { return uid; }
        @Override public int location() { return loc; }
        @Override public int slot() { return slot; }
    }

    @Test
    public void seq1AcceptsAndAdvancesLastSeq() { // LAY-001
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 0, mat128());
        Player p = newPlayer(items, "seq1");
        assertTrue(service.applyBagLayout(p, 1, List.of(new E(a.getId(), ItemLocations.BAG_PAGE, 5))));
        assertEquals(1, items.lastSeq());
        assertEquals(5, items.byUid(a.getId()).getSlot());
        CanvasGrid bag = items.canvas(ItemLocations.BAG_PAGE);
        assertFalse("slot5(2×1 占 5,6) 应被占", bag.canPlace(bag.xOf(5), bag.yOf(5), 2, 1));
        assertTrue("原 slot0 应释放", bag.canPlace(bag.xOf(0), bag.yOf(0), 2, 1));
    }

    @Test
    public void staleSeqDropped() { // LAY-002：seq<=lastSeq 整包丢弃，不改格
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 3, mat128());
        Player p = newPlayer(items, "stale");
        items.setLastSeq(5);
        assertFalse(service.applyBagLayout(p, 4, List.of(new E(a.getId(), ItemLocations.BAG_PAGE, 8))));
        assertEquals("丢弃包不应推进 lastSeq 也不改格", 5, items.lastSeq());
        assertEquals(3, items.byUid(a.getId()).getSlot());
    }

    @Test
    public void unknownUidRejected() { // LAY-003 前半：非本角色/不存在 uid → 拒绝
        PlayerItems items = freshItems();
        Player p = newPlayer(items, "unknown");
        assertFalse(service.applyBagLayout(p, 1, List.of(new E(999L, ItemLocations.BAG_PAGE, 0))));
    }

    @Test
    public void slotOverflowRejected() { // LAY-004：背包 slot=144 越界
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 0, mat128());
        Player p = newPlayer(items, "overflow");
        assertFalse(service.applyBagLayout(p, 1, List.of(new E(a.getId(), ItemLocations.BAG_PAGE, 144))));
        assertFalse(service.applyBagLayout(p, 2, List.of(new E(a.getId(), ItemLocations.WAREHOUSE, 81))));
    }

    @Test
    public void footprintOverflowRejected() { // LAY-005：2×1 放 row 11 → 越界
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 0, mat128());
        Player p = newPlayer(items, "footprint");
        // slot = 11*12+11 = 143 → x=11 y=11, w=2 → x+w=13 > 12
        assertFalse(service.applyBagLayout(p, 1, List.of(new E(a.getId(), ItemLocations.BAG_PAGE, 143))));
    }

    @Test
    public void duplicateTargetRejected() { // LAY-006：两件重复目标格
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 0, mat128());
        ItemInstance b = index(items, 2, ItemLocations.BAG_PAGE, 2, mat128());
        Player p = newPlayer(items, "dup");
        assertFalse(service.applyBagLayout(p, 1,
            List.of(new E(a.getId(), ItemLocations.BAG_PAGE, 10), new E(b.getId(), ItemLocations.BAG_PAGE, 10))));
    }

    @Test
    public void equipToBagOccupiedRejected() { // LAY-008：装备→背包被占 → 拒绝
        PlayerItems items = freshItems();
        ItemInstance eq = index(items, 1, ItemLocations.EQUIP, 1, mat128());
        ItemInstance occupy = index(items, 2, ItemLocations.BAG_PAGE, 5, potion());
        Player p = newPlayer(items, "eq2bag");
        // eq 放 slot=5，但 5 已占
        assertFalse(service.applyBagLayout(p, 1,
            List.of(new E(eq.getId(), ItemLocations.BAG_PAGE, 5))));
        assertTrue(occupy.getSlot() == 5);
    }

    @Test
    public void equipToWarehouseRejected() { // 装备不能直入仓库
        PlayerItems items = freshItems();
        ItemInstance eq = index(items, 1, ItemLocations.EQUIP, 1, mat128());
        Player p = newPlayer(items, "eq2wh");
        assertFalse(service.applyBagLayout(p, 1,
            List.of(new E(eq.getId(), ItemLocations.WAREHOUSE, 0))));
    }

    @Test
    public void crossContainerBagToWarehouse() { // LAY-007 模拟跨容器：背包→仓库
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 3, mat128());
        Player p = newPlayer(items, "cross");
        assertTrue(service.applyBagLayout(p, 1, List.of(new E(a.getId(), ItemLocations.WAREHOUSE, 3))));
        assertEquals(ItemLocations.WAREHOUSE, items.byUid(a.getId()).getLocation());
        assertEquals(3, items.byUid(a.getId()).getSlot());
        CanvasGrid wh = items.canvas(ItemLocations.WAREHOUSE);
        assertFalse("仓库 slot3 应被占", wh.canPlace(wh.xOf(3), wh.yOf(3), 2, 1));
        assertTrue("背包源格已释放", items.canvas(ItemLocations.BAG_PAGE).canPlace(3 % 12, 3 / 12, 2, 1));
    }

    @Test
    public void multiItemAllSettled() { // LAY-007：2 件腾出再落位，位图无残留
        PlayerItems items = freshItems();
        ItemInstance a = index(items, 1, ItemLocations.BAG_PAGE, 0, mat128());
        ItemInstance b = index(items, 2, ItemLocations.BAG_PAGE, 3, mat128());
        Player p = newPlayer(items, "multi");
        assertTrue(service.applyBagLayout(p, 1, List.of(
            new E(a.getId(), ItemLocations.BAG_PAGE, 6),
            new E(b.getId(), ItemLocations.BAG_PAGE, 9))));
        assertEquals(6, items.byUid(a.getId()).getSlot());
        assertEquals(9, items.byUid(b.getId()).getSlot());
        CanvasGrid bag = items.canvas(ItemLocations.BAG_PAGE);
        assertFalse("a(2×1) 占 6,7", bag.canPlace(bag.xOf(6), bag.yOf(6), 2, 1));
        assertFalse("b(2×1) 占 9,10", bag.canPlace(bag.xOf(9), bag.yOf(9), 2, 1));
        assertTrue("原 slot0 释放", bag.canPlace(bag.xOf(0), bag.yOf(0), 2, 1));
        assertTrue("原 slot3 释放", bag.canPlace(bag.xOf(3), bag.yOf(3), 2, 1));
    }

    @Test
    public void emptySnapshotAdvancesSeqWhenOrdered() { // 空快照（客户端清空）有序时推进 seq
        PlayerItems items = freshItems();
        Player p = newPlayer(items, "empty");
        assertTrue(service.applyBagLayout(p, 1, null));
        assertFalse("空序号不推进", items.lastSeq() == 0);
        assertEquals(1, items.lastSeq());
    }
}