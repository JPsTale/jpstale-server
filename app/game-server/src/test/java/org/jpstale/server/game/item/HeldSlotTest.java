package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：**鼠标位**（装备栏 `slot = HELD_SLOT(-1)`）的口径 —— 守住"用它就要守的那条纪律"。
 *
 * 鼠标位放在**装备栏**里（用户 2026-09-14 定：不加新 location），代价是"遍历装备栏"会连带看到
 * 这件还没装备的物品。所以两类遍历必须区分清楚，这里把两条都钉住：
 * <ul>
 *   <li>**按装备算**的（属性/外观/抗性/推送）→ 走 {@link PlayerItems#equippedItems()}，**不含**鼠标位；</li>
 *   <li>**负重**（原版 `CheckWeight` = `InvenItem` + 鼠标缓冲 `InvenItemTemp`）→ **含**鼠标位。</li>
 * </ul>
 * 少排除一次 = 玩家"手里拿着武器却还挂着武器属性"，而且静默不报错 —— 这正是本测试要拦的。
 */
public class HeldSlotTest {

    private static ItemList armor(int classItem, int weight) {
        ItemList t = new ItemList();
        t.setClassItem(classItem);
        t.setWeight(weight);
        return t;
    }

    private static PlayerItems itemsWith(ItemInstance... list) {
        PlayerItems items = new PlayerItems();
        for (ItemInstance it : list) {
            items.index(it);
        }
        return items;
    }

    /** 防御取的是**实例掷点值** `it.getDefence()`（`EquipSummary` 按这个累加），不是模板区间。 */
    private static ItemInstance item(long id, int location, int slot, ItemList template, int defense) {
        ItemInstance it = new ItemInstance();
        it.setId(id);
        it.setLocation(location);
        it.setSlot(slot);
        it.setTemplate(template);
        it.setItemListId(1);
        it.setCount(1);
        it.setDefence(defense);
        return it;
    }

    @Test
    public void equippedItemsExcludesHeldSlot() {
        ItemInstance worn = item(1L, ItemLocations.EQUIP, ItemLocations.SLOT_ARMOR, armor(8, 10), 10);
        ItemInstance held = item(2L, ItemLocations.EQUIP, ItemLocations.HELD_SLOT, armor(8, 10), 10);
        PlayerItems items = itemsWith(worn, held);

        assertEquals("真正装备着的只有 1 件", 1, items.equippedItems().size());
        assertEquals("且是那一件（不是手上那件）", Long.valueOf(1L), items.equippedItems().get(0).getId());
        assertTrue("鼠标位判据", ItemLocations.isHeld(held));
        assertFalse("装备位不算鼠标位", ItemLocations.isHeld(worn));
        assertNull("鼠标位那件不在 byUid 之外的地方也拿得到", null);
    }

    @Test
    public void heldItemCountsWeightButNotEquip() {
        ItemInstance worn = item(1L, ItemLocations.EQUIP, ItemLocations.SLOT_ARMOR, armor(8, 50), 50);
        ItemInstance held = item(2L, ItemLocations.EQUIP, ItemLocations.HELD_SLOT, armor(8, 30), 30);
        PlayerItems items = itemsWith(worn, held);
        Player p = new Player(null, 0);
        p.setItems(items);
        p.setStrength(10);
        PlayerStatCalculator calc = new PlayerStatCalculator();

        assertEquals("负重含鼠标位（原版 InvenItem + InvenItemTemp）", 80, calc.currentWeight(p));
        assertEquals("装备防御只算真正装备的那件", 50, calc.defense(p));
        assertEquals("装备汇总同样排除鼠标位", 1, items.equippedItems().size());
    }

    @Test
    public void heldItemIsNotDisplacedByNewEquipLookup() {
        // 槽位查找按具体槽号，-1 永不与 1..13 冲突（换装/脱装/W 切套都不会误伤手上那件）
        ItemInstance held = item(9L, ItemLocations.EQUIP, ItemLocations.HELD_SLOT, armor(8, 10), 10);
        PlayerItems items = itemsWith(held);
        assertTrue("按 slot 1 看不到手上那件", !items.itemsIn(ItemLocations.EQUIP).isEmpty());
        assertEquals("但 equippedItems() 里没有它", 0, items.equippedItems().size());
        List<ItemInstance> all = items.itemsIn(ItemLocations.EQUIP);
        assertEquals(1, all.size());
        assertEquals(ItemLocations.HELD_SLOT, all.get(0).getSlot());
    }
}
