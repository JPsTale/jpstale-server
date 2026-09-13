package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：**拾取药水优先填药水槽** —— 原版 `cINVENTORY::AutoSetPotion`（由 `AutoSetInvenItem` 调用）。
 *
 * 规则：逐槽 11→12→13，**同种未满则补满**、**空槽放 min(剩余, 容量)**、异种/已满跳过；余数才走背包/手上。
 * ⚠ 与原版的有意差异：原版只用**第一个能用的槽**（该槽满了余数直接进背包），我们**把所有能用的槽都灌满**
 * （用户 2026-09-14 的期望），本测试钉住的是**我们这条**。
 */
public class PotionAutoFillTest {

    /** 药水模板：classItem = 0x2000 (POTION)；potioncount 同时是"无臂环时的槽容量"（实测 15 瓶药水都是 2）。 */
    private static ItemList potion(int id, String name, int potionCount) {
        ItemList t = new ItemList();
        t.setId(id);
        t.setName(name);
        t.setClassItem(ItemClass.POTION);
        t.setPotionCount(potionCount);
        t.setWidth(22);
        t.setHeight(22);
        t.setWeight(1);
        return t;
    }

    private static ItemStorageService fakeStorage() {
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> {
            String n = method.getName();
            if (n.equals("update") || n.equals("insert") || n.equals("updateById") || n.equals("restore")) {
                if (n.equals("insert") && args.length > 0 && args[0] instanceof ItemInstance) {
                    ((ItemInstance) args[0]).setId(900L + Math.abs(((ItemInstance) args[0]).getItemListId()));
                }
                return 1;
            }
            return method.getDefaultValue();
        };
        ItemMapper mapper = (ItemMapper) Proxy.newProxyInstance(
                ItemMapper.class.getClassLoader(), new Class<?>[]{ItemMapper.class}, h);
        return new ItemStorageService(mapper);
    }

    private final ItemService service = new ItemService(new ItemRollService(null), fakeStorage(), new PlayerStatCalculator());

    private static Player newPlayer(PlayerItems items) {
        Player p = new Player(null, 1);
        p.setItems(items);
        p.setCharacterId(1L);   // 不依赖 session（Player.characterId 注释即为此）
        p.setName("tester");
        p.setStrength(50);
        return p;
    }

    private static ItemInstance stack(long uid, ItemList def, int count) {
        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setCharacterId(1);
        it.setLocation(ItemLocations.BAG_PAGE);
        it.setSlot(0);
        it.setTemplate(def);
        it.setItemListId(Math.toIntExact(def.getId()));
        it.setCount(count);
        return it;
    }

    private static ItemInstance inSlot(PlayerItems items, int slot, ItemList def, int count) {
        ItemInstance it = new ItemInstance();
        it.setId(100L + slot);
        it.setCharacterId(1);
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(slot);
        it.setTemplate(def);
        it.setItemListId(Math.toIntExact(def.getId()));
        it.setCount(count);
        items.index(it);
        return it;
    }

    @Test
    public void poursIntoAllUsableSlotsThenLeavesRemainder() {
        ItemList life = potion(426, "Mystic Life Potion", 2);   // 无臂环 → 每槽容量 2
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);

        ItemInstance fresh = stack(1L, life, 5);                 // 地上拾取 5 瓶（实例未入容器）
        List<ItemInstance> touched = service.pourIntoPotionSlots(p, fresh);

        assertEquals("三个槽都被填充", 3, touched.size());
        assertEquals("槽1 满", 2, items.at(ItemLocations.EQUIP, 11).getCount());
        assertEquals("槽2 满", 2, items.at(ItemLocations.EQUIP, 12).getCount());
        assertEquals("槽3 收下剩余 1 瓶", 1, items.at(ItemLocations.EQUIP, 13).getCount());
        assertEquals("源堆被灌空（余数 0）", 0, fresh.getCount());
    }

    @Test
    public void topsUpSameTypeAndSkipsOtherTypes() {
        ItemList life = potion(426, "Mystic Life Potion", 2);
        ItemList mana = potion(430, "Mystic Mana Potion", 2);
        PlayerItems items = new PlayerItems();
        inSlot(items, 11, mana, 1);        // 槽1 是别的药水 → 跳过
        inSlot(items, 12, life, 1);        // 槽2 同种未满 → 补到 2
        Player p = newPlayer(items);

        ItemInstance fresh = stack(1L, life, 4);
        List<ItemInstance> touched = service.pourIntoPotionSlots(p, fresh);

        assertEquals("动了槽2 + 槽3", 2, touched.size());
        assertEquals("异种槽1 不动", 1, items.at(ItemLocations.EQUIP, 11).getCount());
        assertEquals("同种槽2 补满", 2, items.at(ItemLocations.EQUIP, 12).getCount());
        assertEquals("空槽3 装 2 瓶（容量 2；此时还剩 3 瓶）", 2, items.at(ItemLocations.EQUIP, 13).getCount());
        assertEquals("源堆余 1 瓶 → 交给背包/手上", 1, fresh.getCount());
    }

    @Test
    public void wholeStackFitsIntoOneSlotAndSourceIsDrained() {
        ItemList life = potion(426, "Mystic Life Potion", 5);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);

        ItemInstance fresh = stack(7L, life, 3);              // 容量 5 ≥ 3 → 整堆进槽1
        List<ItemInstance> touched = service.pourIntoPotionSlots(p, fresh);

        assertEquals(1, touched.size());
        assertEquals("槽1 收下 3 瓶", 3, items.at(ItemLocations.EQUIP, 11).getCount());
        assertEquals("落在槽1", 11, touched.get(0).getSlot());
        // 契约：源**只会被扣减**（调用方据此判断"还有余数"）——整堆也拆成新行，不搬走源
        assertEquals("源被扣空（= 没有余数）", 0, fresh.getCount());
        assertTrue("槽里那条是新行，不是源实例本身", touched.get(0) != fresh);
    }

    @Test
    public void nonPotionIsNoOp() {
        ItemList sword = new ItemList();
        sword.setId(80);
        sword.setClassItem(4);                                // 单手武器
        sword.setPotionCount(0);
        PlayerItems items = new PlayerItems();
        Player p = newPlayer(items);

        ItemInstance fresh = stack(1L, sword, 1);
        List<ItemInstance> touched = service.pourIntoPotionSlots(p, fresh);

        assertTrue("非药水不碰药水槽", touched.isEmpty());
        assertEquals("源不动", 1, fresh.getCount());
    }
}
