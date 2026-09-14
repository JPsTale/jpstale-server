package org.jpstale.server.game.item;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.userdb.entity.Item;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;

/**
 * 物品类测试的公共夹具（**唯一实现** —— 曾经每个测试各抄一份假存储，抄歪了一次：
 * 旧版把 id 写到 `ItemInstance` 上，而真实 `ItemStorageService.insert` 是 `it.setId(r.getId())`
 * ——**行**才有 id ⇒ 新行的 id 一直是 null ⇒ `markDirty(..., fresh.getId())` 自动拆箱 NPE）。
 */
final class ItemTestSupport {

    private ItemTestSupport() {
    }

    private static final AtomicLong SEQ = new AtomicLong(1000);

    /** 假存储：insert 时给**行**分配唯一 id（必须唯一：`byUid` 是按 id 索引的，撞 id = 静默丢件）。 */
    static ItemStorageService fakeStorage() {
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> {
            String n = method.getName();
            if (n.equals("insert") && args != null && args.length > 0 && args[0] instanceof Item) {
                ((Item) args[0]).setId(SEQ.incrementAndGet());
                return 1;
            }
            return n.equals("updateById") || n.equals("update") || n.equals("restore")
                    || n.equals("softDelete") ? 1 : method.getDefaultValue();
        };
        ItemMapper mapper = (ItemMapper) Proxy.newProxyInstance(
                ItemMapper.class.getClassLoader(), new Class<?>[]{ItemMapper.class}, h);
        return new ItemStorageService(mapper);
    }

    static ItemService newService() {
        return new ItemService(new ItemRollService(null), fakeStorage(), new PlayerStatCalculator());
    }

    /** 药水模板：classItem = 0x2000 (POTION)；potionCount 同时是"无臂环时的槽容量"。 */
    static ItemList potion(int id, String name, int potionCount) {
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

    static Player newPlayer(PlayerItems items) {
        Player p = new Player(null, 1);
        p.setItems(items);
        p.setCharacterId(1L);   // 不依赖 session（Player.characterId 注释即为此）
        p.setName("tester");
        p.setStrength(50);
        return p;
    }

    static ItemInstance instance(long uid, ItemList def, int count, int location, int slot) {
        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setCharacterId(1);
        it.setTemplate(def);
        it.setItemListId(Math.toIntExact(def.getId()));
        it.setCount(count);
        it.setLocation(location);
        it.setSlot(slot);
        return it;
    }

    /** 未入容器的实例（拾取/发放语义：id 已定但没有容器索引）。 */
    static ItemInstance stack(long uid, ItemList def, int count) {
        return instance(uid, def, count, ItemLocations.BAG_PAGE, 0);
    }

    /** 直接登记在装备栏/药水槽某格。 */
    static ItemInstance inSlot(PlayerItems items, int slot, ItemList def, int count) {
        ItemInstance it = instance(100L + slot, def, count, ItemLocations.EQUIP, slot);
        items.index(it);
        return it;
    }

    /** 放进背包画布某格。 */
    static ItemInstance inBag(PlayerItems items, long uid, int slot, ItemList def, int count) {
        ItemInstance it = instance(uid, def, count, ItemLocations.BAG, slot);
        items.putToCanvas(ItemLocations.BAG, slot, it);
        return it;
    }
}
