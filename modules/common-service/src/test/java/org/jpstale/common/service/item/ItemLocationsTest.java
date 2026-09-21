package org.jpstale.common.service.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ItemLocations} 容器/槽位编码的特征测试。
 *
 * 这些值就是 `userdb.item.location/slot` 的落库编码，改动等于改存档语义。
 */
class ItemLocationsTest {

    @Test
    void 容器编码是十进制分段() {
        assertEquals(0, ItemLocations.EQUIP);
        assertEquals(1, ItemLocations.BACKUP_EQUIP);
        assertEquals(10, ItemLocations.BAG_PAGE);
        assertEquals(20, ItemLocations.QUEST);
        assertEquals(30, ItemLocations.WAREHOUSE);
        assertEquals(40, ItemLocations.STORE);
        assertEquals(10, ItemLocations.BAG_PAGE_BASE);
        assertEquals(30, ItemLocations.WAREHOUSE_PAGE_BASE);
        assertEquals(ItemLocations.BAG_PAGE, ItemLocations.BAG);
        assertEquals(ItemLocations.BACKUP_EQUIP, ItemLocations.BACKUP_WEAPON);
    }

    @Test
    void 画布尺寸() {
        assertEquals(12, ItemLocations.BAG_W);
        assertEquals(12, ItemLocations.BAG_H);
        assertEquals(144, ItemLocations.BAG_SLOTS);
        assertEquals(9, ItemLocations.WH_W);
        assertEquals(9, ItemLocations.WH_H);
        assertEquals(81, ItemLocations.WH_SLOTS);
    }

    @Test
    void 装备槽号是一到十三且互不相同() {
        int[] slots = {
                ItemLocations.SLOT_MAIN_HAND, ItemLocations.SLOT_OFF_HAND, ItemLocations.SLOT_ARMOR,
                ItemLocations.SLOT_AMULET, ItemLocations.SLOT_RING_R, ItemLocations.SLOT_RING_L,
                ItemLocations.SLOT_SHELTOM, ItemLocations.SLOT_ARMLET, ItemLocations.SLOT_GLOVES,
                ItemLocations.SLOT_BOOTS, ItemLocations.SLOT_POTION_1, ItemLocations.SLOT_POTION_2,
                ItemLocations.SLOT_POTION_3
        };
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < slots.length; i++) {
            assertEquals(i + 1, slots[i], "槽号必须连续从 1 开始且按声明顺序递增");
            assertTrue(seen.add(slots[i]), "槽号重复: " + slots[i]);
        }
        assertEquals(13, slots.length);
    }

    /** 鼠标位 = 装备栏的 -1 号槽；真实装备槽是 1~13，故 -1 不会冲突。 */
    @Test
    void 鼠标位是装备栏负一槽() {
        assertEquals(-1, ItemLocations.HELD_SLOT);

        ItemInstance held = new ItemInstance();
        held.setLocation(ItemLocations.EQUIP);
        held.setSlot(ItemLocations.HELD_SLOT);
        assertTrue(ItemLocations.isHeld(held));

        ItemInstance equipped = new ItemInstance();
        equipped.setLocation(ItemLocations.EQUIP);
        equipped.setSlot(ItemLocations.SLOT_MAIN_HAND);
        assertFalse(ItemLocations.isHeld(equipped), "真装备槽不算手持");

        ItemInstance inBag = new ItemInstance();
        inBag.setLocation(ItemLocations.BAG_PAGE);
        inBag.setSlot(ItemLocations.HELD_SLOT);
        assertFalse(ItemLocations.isHeld(inBag), "背包里的 -1 格不算手持");

        assertFalse(ItemLocations.isHeld(null));
    }

    @Test
    void 画布查询只认背包与仓库() {
        assertTrue(ItemLocations.isCanvas(ItemLocations.BAG_PAGE));
        assertTrue(ItemLocations.isCanvas(ItemLocations.WAREHOUSE));
        assertFalse(ItemLocations.isCanvas(ItemLocations.EQUIP));
        assertFalse(ItemLocations.isCanvas(ItemLocations.BACKUP_EQUIP));

        assertEquals(12, ItemLocations.widthOf(ItemLocations.BAG_PAGE));
        assertEquals(9, ItemLocations.widthOf(ItemLocations.WAREHOUSE));
        assertEquals(0, ItemLocations.widthOf(ItemLocations.EQUIP));
        assertEquals(144, ItemLocations.slotsOf(ItemLocations.BAG_PAGE));
        assertEquals(81, ItemLocations.slotsOf(ItemLocations.WAREHOUSE));
        assertEquals(0, ItemLocations.slotsOf(ItemLocations.EQUIP));
    }

    @Test
    void 分段区间是十到十九与三十到三十九() {
        assertTrue(ItemLocations.isBagPage(10));
        assertTrue(ItemLocations.isBagPage(19));
        assertFalse(ItemLocations.isBagPage(20));
        assertFalse(ItemLocations.isBagPage(9));
        assertTrue(ItemLocations.isWarehousePage(30));
        assertTrue(ItemLocations.isWarehousePage(39));
        assertFalse(ItemLocations.isWarehousePage(40));
        assertFalse(ItemLocations.isWarehousePage(29));
    }
}
