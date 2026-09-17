package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jpstale.dao.userdb.entity.Item;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import org.junit.Test;

/**
 * L0：**多行互换的写序** —— 守住"唯一键 `(character_id, location, slot)` 在任何时刻都不被撞"。
 *
 * 这条不变式没法靠"看代码"长期保证：唯一索引是**逐语句立即检查**的，所以"把 A 写到 X，
 * 而 B 的行还占着 X"会当场报重复键。`ItemStorageService.writeMoved` 的做法是
 * **先把每行停到各不相同的哨兵槽（-2、-3…）再逐个写回最终位置**。
 *
 * 本测试用动态代理做一个**会记账的假 mapper**：把每次写应用到一份"模拟数据库"上，
 * 每一步都检查有没有两行占同一个 `(characterId, location, slot)` —— 正是生产库里那条唯一键。
 * 于是写序错了就会在这里红，而不是等到玩家互换装备时炸在线上。
 */
public class WriteMovedOrderTest {

    /** 模拟库：id → {characterId, location, slot} */
    private final Map<Long, int[]> db = new HashMap<>();
    private final List<String> violations = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();

    private ItemStorageService storage() {
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> {
            switch (method.getName()) {
                case "updateById" -> {          // 停车：只带 id + slot
                    Item r = (Item) args[0];
                    apply(r.getId(), null, r.getLocation(), r.getSlot(), "updateById");
                    return 1;
                }
                case "update" -> {              // 落地：实体带全字段（id/location/slot）
                    Item r = (Item) args[0];
                    apply(r.getId(), r.getCharacterId(), r.getLocation(), r.getSlot(), "update");
                    return 1;
                }
                case "insert" -> {
                    Item r = (Item) args[0];
                    r.setId((long) (db.size() + 1000));
                    apply(r.getId(), r.getCharacterId(), r.getLocation(), r.getSlot(), "insert");
                    return 1;
                }
                default -> {
                    return method.getDefaultValue();
                }
            }
        };
        ItemMapper mapper = (ItemMapper) Proxy.newProxyInstance(
                ItemMapper.class.getClassLoader(), new Class<?>[]{ItemMapper.class}, h);
        return new ItemStorageService(mapper);
    }

    /** 应用一次写并**立刻检查唯一键**（模拟数据库的逐语句检查）。 */
    private void apply(Long id, Integer cid, Short location, Short slot, String op) {
        if (id == null) {
            return;
        }
        int[] row = db.get(id);
        if (row == null) {
            row = new int[]{cid == null ? 1 : cid, location == null ? 0 : location, slot == null ? 0 : slot};
            db.put(id, row);
        } else {
            if (location != null) {
                row[1] = location;
            }
            if (slot != null) {
                row[2] = slot;
            }
        }
        trace.add(op + " id=" + id + " loc=" + row[1] + " slot=" + row[2]);
        // 唯一键检查：同一 (characterId, location, slot) 不允许两行
        for (Map.Entry<Long, int[]> a : db.entrySet()) {
            for (Map.Entry<Long, int[]> b : db.entrySet()) {
                if (a.getKey().equals(b.getKey())) {
                    continue;
                }
                if (a.getValue()[0] == b.getValue()[0] && a.getValue()[1] == b.getValue()[1]
                        && a.getValue()[2] == b.getValue()[2]) {
                    violations.add("撞唯一键: id " + a.getKey() + " 与 " + b.getKey()
                            + " 都在 (loc=" + a.getValue()[1] + ", slot=" + a.getValue()[2] + ") —— " + trace);
                }
            }
        }
    }

    private static ItemInstance item(long id, int location, int slot) {
        ItemInstance it = new ItemInstance();
        it.setId(id);
        it.setCharacterId(1);
        it.setLocation(location);
        it.setSlot(slot);
        it.setItemListId(1);
        it.setCount(1);
        return it;
    }

    @Test
    public void twoItemSwapNeverCollides() {
        // 背包里两件互换：A(10,0) ↔ B(10,1) —— 老写法"先写 A 到 slot1"会当场撞 B
        ItemInstance a = item(1L, ItemLocations.BAG_PAGE, 0);
        ItemInstance b = item(2L, ItemLocations.BAG_PAGE, 1);
        db.put(1L, new int[]{1, ItemLocations.BAG_PAGE, 0});
        db.put(2L, new int[]{1, ItemLocations.BAG_PAGE, 1});

        // 内存先落位（与真实调用方一致），再交给 writeMoved 写库
        a.setSlot(1);
        b.setSlot(0);
        storage().writeMoved(List.of(a, b));

        assertTrue("任何一步都不允许撞唯一键：\n" + String.join("\n", violations), violations.isEmpty());
        assertEquals("最终 A 在 slot1", 1, db.get(1L)[2]);
        assertEquals("最终 B 在 slot0", 0, db.get(2L)[2]);
        // 快路：**恰好两件**只停一件 → 停 A、写 B、写 A = 3 条（用户 2026-09-14 要求）
        assertEquals("两件互换应为 3 条 UPDATE", 3, trace.size());
        assertTrue("第一条是停车（哨兵槽 -2）：" + trace.get(0), trace.get(0).contains("slot=-2"));
        assertTrue("第二条写另一件到最终位置：" + trace.get(1),
                trace.get(1).contains("id=2") && trace.get(1).contains("slot=0"));
        assertTrue("第三条写被停的那件：" + trace.get(2),
                trace.get(2).contains("id=1") && trace.get(2).contains("slot=1"));
    }

    @Test
    public void equipSwapAcrossLocationsNeverCollides() {
        // 鼠标位(0,-1) 那件装进主手(0,1)，被换下的旧件要回鼠标位(0,-1) —— 典型互换
        ItemInstance fromHand = item(1L, ItemLocations.EQUIP, ItemLocations.HELD_SLOT);
        ItemInstance oldWorn = item(2L, ItemLocations.EQUIP, ItemLocations.SLOT_MAIN_HAND);
        db.put(1L, new int[]{1, ItemLocations.EQUIP, ItemLocations.HELD_SLOT});
        db.put(2L, new int[]{1, ItemLocations.EQUIP, ItemLocations.SLOT_MAIN_HAND});

        fromHand.setSlot(ItemLocations.SLOT_MAIN_HAND);
        oldWorn.setSlot(ItemLocations.HELD_SLOT);
        storage().writeMoved(List.of(fromHand, oldWorn));

        assertTrue("任何一步都不允许撞唯一键：\n" + String.join("\n", violations), violations.isEmpty());
        assertEquals(ItemLocations.SLOT_MAIN_HAND, db.get(1L)[2]);
        assertEquals(ItemLocations.HELD_SLOT, db.get(2L)[2]);
    }

    @Test
    public void threeCycleNeverCollides() {
        // 三件轮换（客户端整理背包可能一次报多件）：A→B 的格、B→C 的格、C→A 的格
        ItemInstance a = item(1L, ItemLocations.BAG_PAGE, 0);
        ItemInstance b = item(2L, ItemLocations.BAG_PAGE, 1);
        ItemInstance c = item(3L, ItemLocations.BAG_PAGE, 2);
        db.put(1L, new int[]{1, ItemLocations.BAG_PAGE, 0});
        db.put(2L, new int[]{1, ItemLocations.BAG_PAGE, 1});
        db.put(3L, new int[]{1, ItemLocations.BAG_PAGE, 2});

        a.setSlot(1);
        b.setSlot(2);
        c.setSlot(0);
        storage().writeMoved(List.of(a, b, c));

        assertTrue("任何一步都不允许撞唯一键：\n" + String.join("\n", violations), violations.isEmpty());
        assertEquals(1, db.get(1L)[2]);
        assertEquals(2, db.get(2L)[2]);
        assertEquals(0, db.get(3L)[2]);
    }
}
