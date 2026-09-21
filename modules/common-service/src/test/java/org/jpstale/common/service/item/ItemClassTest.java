package org.jpstale.common.service.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ItemClass} 槽位位掩码的特征测试。
 *
 * 钉住的是**位值本身**：它们与原版 `sinItem.h:16-32` 逐位对应，动一个位就是动协议/存档语义。
 * 客户端 `src/game/itemClass.ts` 是同一份定义的 TS 侧，位值必须一致。
 */
class ItemClassTest {

    @Test
    void 位值与原版逐位一致() {
        assertEquals(0x0001, ItemClass.BOX);
        assertEquals(0x0002, ItemClass.LHAND);
        assertEquals(0x0004, ItemClass.RHAND);
        assertEquals(0x0008, ItemClass.ARMOR);
        assertEquals(0x0010, ItemClass.BOOTS);
        assertEquals(0x0020, ItemClass.GLOVES);
        assertEquals(0x0040, ItemClass.LRING);
        assertEquals(0x0080, ItemClass.RRING);
        assertEquals(0x0100, ItemClass.SHELTOM);
        assertEquals(0x0200, ItemClass.AMULET);
        assertEquals(0x0800, ItemClass.ARMLET);
        assertEquals(0x1000, ItemClass.TWO_HAND_FLAG);
        assertEquals(0x2000, ItemClass.POTION);
        assertEquals(0x4000, ItemClass.COSTUME);
        assertEquals(0x8000, ItemClass.WING_RIGHT);
        assertEquals(0x10000, ItemClass.EARRING_L);
        assertEquals(0x20000, ItemClass.EARRING_R);
    }

    @Test
    void 语义别名是位值的组合而不是新位() {
        assertEquals(ItemClass.LHAND, ItemClass.OFF_HAND);
        assertEquals(ItemClass.RHAND, ItemClass.ONE_HAND_WEAPON);
        assertEquals(ItemClass.RHAND | ItemClass.LHAND, ItemClass.TWO_HAND_WEAPON);
        assertEquals(ItemClass.LRING | ItemClass.RRING, ItemClass.RING);
        assertEquals(ItemClass.SHELTOM, ItemClass.GEM);
        // 6 = 单手(4) | 双手位(2)：原版 ITEM_CLASS_WEAPON_TWO，不是 TWO_HAND_FLAG(0x1000)
        assertEquals(6, ItemClass.TWO_HAND_WEAPON);
        assertTrue((ItemClass.TWO_HAND_WEAPON & ItemClass.TWO_HAND_FLAG) == 0);
    }

    @Test
    void isWeapon只认单手与双手() {
        assertTrue(ItemClass.isWeapon(ItemClass.ONE_HAND_WEAPON));
        assertTrue(ItemClass.isWeapon(ItemClass.TWO_HAND_WEAPON));
        assertFalse(ItemClass.isWeapon(ItemClass.ARMOR));
        assertFalse(ItemClass.isWeapon(ItemClass.POTION));
        assertFalse(ItemClass.isWeapon(0));
    }

    @Test
    void isTwoHandWeapon只看六() {
        assertTrue(ItemClass.isTwoHandWeapon(6));
        assertFalse(ItemClass.isTwoHandWeapon(4));
        // 0x1000 是原版保留位、全仓无使用点，**不是**双手判据
        assertFalse(ItemClass.isTwoHandWeapon(ItemClass.TWO_HAND_FLAG));
    }

    @Test
    void 肢体防具与躯干判定() {
        assertTrue(ItemClass.isBodyGear(ItemClass.ARMOR));
        assertTrue(ItemClass.isBodyGear(ItemClass.BOOTS));
        assertTrue(ItemClass.isBodyGear(ItemClass.GLOVES));
        assertFalse(ItemClass.isBodyGear(ItemClass.AMULET));
        assertFalse(ItemClass.isBodyGear(ItemClass.OFF_HAND));
        assertTrue(ItemClass.isTorsoArmor(ItemClass.ARMOR));
        assertFalse(ItemClass.isTorsoArmor(ItemClass.BOOTS));
    }

    /** 药水有槽位位值（0x2000 快捷槽）但**必须能堆叠** —— 曾漏判导致背包药水无法合并。 */
    @Test
    void 可堆叠者含无槽位零一与药水() {
        assertTrue(ItemClass.isStackable(0));
        assertTrue(ItemClass.isStackable(1));
        assertTrue(ItemClass.isStackable(ItemClass.POTION));
        assertFalse(ItemClass.isStackable(ItemClass.ONE_HAND_WEAPON));
        assertFalse(ItemClass.isStackable(ItemClass.ARMOR));
        assertFalse(ItemClass.isStackable(ItemClass.ARMLET));
    }
}
