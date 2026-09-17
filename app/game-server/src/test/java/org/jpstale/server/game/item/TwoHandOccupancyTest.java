package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
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
 * L0：**双手武器占两只手** —— 装了双手武器后，副手放不进东西；硬放会把双手武器**换到鼠标位**。
 *
 * 原版机制（`sinInvenTory.cpp` 的 `OverlapTwoHandItem` / `OverlapTwoHandSwitch`，两者都只在
 * "放进去的这件是双手武器"时动作）：规则是**涌现**的 —— 双手武器在槽 1 时 `sInven[1].ItemIndex`
 * **也指向那一件**（副手格"被占位"），于是往副手放任何东西都与它撞件 → 走换手把它拿到鼠标位。
 * 我们原先只在客户端显示层做了这个"占位镜像"，服务端模型里它只在槽 1 ⇒ 盾直接装上了（用户实测）。
 */
public class TwoHandOccupancyTest {

    private static ItemList weapon(int id, int classItem, int w, int h) {
        ItemList t = new ItemList();
        t.setId(id);
        t.setName("it" + id);
        t.setClassItem(classItem);
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
    public void offHandEquipTakesTwoHandedWeaponToMouse() {
        ItemList dagger2h = weapon(715, ItemClass.TWO_HAND_WEAPON, 22, 44);   // 双手匕首
        ItemList shield = weapon(409, ItemClass.OFF_HAND, 44, 44);            // 副手盾
        PlayerItems items = new PlayerItems();
        ItemInstance worn = inst(1L, ItemLocations.EQUIP, ItemLocations.SLOT_MAIN_HAND, dagger2h);
        ItemInstance bagged = inst(2L, ItemLocations.BAG_PAGE, 0, shield);
        items.index(worn);
        items.index(bagged);
        Player p = newPlayer(items);

        ItemService.OpResult r = service.equipFromBag(p, 2L, ItemLocations.SLOT_OFF_HAND);

        assertEquals("盾装进副手", ItemService.OpReason.OK, r.reason);
        assertEquals("盾在副手槽", ItemLocations.SLOT_OFF_HAND, items.byUid(2L).getSlot());
        assertTrue("双手武器被换到**鼠标位**（不是还留在主手）", ItemLocations.isHeld(items.byUid(1L)));
    }

    @Test
    public void twoHandedWeaponToEmptyMainHandTakesOffHandItemToMouse() {
        // 用户实测（2026-09-14）：**副手盾 + 主手空** + 装双手武器 → 盾应**交换到鼠标位**（不是回背包）。
        // 依据 `sinInvenTory.cpp`：主手槽为空 → 走 `OverlapTwoHandSwitch`，把**另一只手那件**标成"撞件"
        // → 撞件进鼠标位（`memcpy(pItem, &TempItem)`，pItem 就是鼠标位）。
        ItemList dagger2h = weapon(715, ItemClass.TWO_HAND_WEAPON, 22, 44);
        ItemList shield = weapon(409, ItemClass.OFF_HAND, 44, 44);
        PlayerItems items = new PlayerItems();
        ItemInstance off = inst(1L, ItemLocations.EQUIP, ItemLocations.SLOT_OFF_HAND, shield);
        ItemInstance bagged = inst(2L, ItemLocations.BAG_PAGE, 0, dagger2h);
        items.index(off);
        items.index(bagged);
        Player p = newPlayer(items);

        ItemService.OpResult r = service.equipFromBag(p, 2L, ItemLocations.SLOT_MAIN_HAND);

        assertEquals("双手武器装进主手", ItemService.OpReason.OK, r.reason);
        assertEquals("双手武器在主手", ItemLocations.SLOT_MAIN_HAND, items.byUid(2L).getSlot());
        assertTrue("副手盾被换到**鼠标位**", ItemLocations.isHeld(items.byUid(1L)));
    }

    @Test
    public void occupiedMainHandTakesMouseSoOtherHandFallsBackToBag() {
        // 优先级用例：主手有单手剑 + 副手有盾，再把双手武器装进主手 →
        // 撞件 = **主手那把剑** → 它进鼠标位；副手盾走 AutoSetItemIndex → **背包**（原版同此）。
        ItemList sword = weapon(80, ItemClass.ONE_HAND_WEAPON, 22, 44);
        ItemList shield = weapon(409, ItemClass.OFF_HAND, 44, 44);
        ItemList dagger2h = weapon(715, ItemClass.TWO_HAND_WEAPON, 22, 44);
        PlayerItems items = new PlayerItems();
        ItemInstance worn = inst(1L, ItemLocations.EQUIP, ItemLocations.SLOT_MAIN_HAND, sword);
        ItemInstance off = inst(2L, ItemLocations.EQUIP, ItemLocations.SLOT_OFF_HAND, shield);
        ItemInstance bagged = inst(3L, ItemLocations.BAG_PAGE, 0, dagger2h);
        items.index(worn);
        items.index(off);
        items.index(bagged);
        Player p = newPlayer(items);

        ItemService.OpResult r = service.equipFromBag(p, 3L, ItemLocations.SLOT_MAIN_HAND);

        assertEquals(ItemService.OpReason.OK, r.reason);
        assertTrue("撞件（主手那把剑）进鼠标位", ItemLocations.isHeld(items.byUid(1L)));
        assertEquals("另一只手的盾回背包（手位已被撞件占了）",
                ItemLocations.BAG_PAGE, items.byUid(2L).getLocation());
        assertEquals("双手武器在主手", ItemLocations.SLOT_MAIN_HAND, items.byUid(3L).getSlot());
    }
}
