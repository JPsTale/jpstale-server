package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.ItemList;

/**
 * 物品 → 装备槽位映射（原版 CheckInvenItemPosition 语义）。
 * <p>
 * classItem 即 INVENTORY_POS 位值（见 {@link ItemClass}）——本类只做"位值→槽位"映射。
 */
public final class EquipSlots {

    private EquipSlots() {
    }

    /**
     * 该物品允许穿入的装备槽位（可多个，如戒指左/右）。非装备物返回空数组。
     */
    public static int[] allowedSlots(ItemList def) {
        if (def == null || def.getClassItem() == null) {
            return new int[0];
        }
        return switch (def.getClassItem()) {
            case ItemClass.OFF_HAND -> new int[]{ItemLocations.SLOT_OFF_HAND};      // 盾/法球/魔杖
            case ItemClass.ONE_HAND_WEAPON -> new int[]{ItemLocations.SLOT_MAIN_HAND};     // 单手武器
            case ItemClass.TWO_HAND_WEAPON -> new int[]{ItemLocations.SLOT_MAIN_HAND};     // 双手武器（占 1+2）
            case ItemClass.ARMOR -> new int[]{ItemLocations.SLOT_ARMOR};            // 铠甲/法袍
            case ItemClass.BOOTS -> new int[]{ItemLocations.SLOT_BOOTS};            // 靴子
            case ItemClass.GLOVES -> new int[]{ItemLocations.SLOT_GLOVES};          // 护手
            case ItemClass.RING -> new int[]{ItemLocations.SLOT_RING_R, ItemLocations.SLOT_RING_L};
            case ItemClass.GEM -> new int[]{ItemLocations.SLOT_SHELTOM};            // 宝石
            case ItemClass.AMULET -> new int[]{ItemLocations.SLOT_AMULET};          // 项链
            case ItemClass.ARMLET -> new int[]{ItemLocations.SLOT_ARMLET};          // 护腕
            case ItemClass.POTION -> new int[]{ItemLocations.SLOT_POTION_1, ItemLocations.SLOT_POTION_2,
                    ItemLocations.SLOT_POTION_3};                                   // 药水（可快捷槽）
            case ItemClass.COSTUME -> new int[0];                                   // 时装（本轮不启用）
            default -> new int[0];                                                  // 0/1 消耗材料等 → 背包物
        };
    }

    /** 是否装备物（有可穿槽位）。 */
    public static boolean isEquipable(ItemList def) {
        return allowedSlots(def).length > 0;
    }

    /**
     * 是否药水（可放进药水快捷槽的消耗品）。
     * 判据用**槽位位值** `INVENTORY_POS_POTION`(0x2000)，不用 code 前缀 ——
     * 实测 EU 物品表里 15 瓶药水（pl101-105/pm101-105/ps101-105）classItem 全是 8192，
     * 而 NewSourcePT 的 `PM1` 前缀在 EU 表里 0 行（那是另一代的编码）。
     */
    public static boolean isPotion(ItemList def) {
        return def != null && def.getClassItem() != null && def.getClassItem() == ItemClass.POTION;
    }

    /** 是否药水快捷槽（ITEMSLOT 11/12/13，原版 PotionOne/Two/Three）。 */
    public static boolean isPotionSlot(int slot) {
        return slot >= ItemLocations.SLOT_POTION_1 && slot <= ItemLocations.SLOT_POTION_3;
    }

    /** 是否双手武器（classItem=6）。 */
    public static boolean isTwoHand(ItemList def) {
        return def != null && def.getClassItem() != null
            && ItemClass.isTwoHandWeapon(def.getClassItem());
    }

    /** 该槽位是否允许当前物品进入。 */
    public static boolean slotAllows(ItemList def, int slot) {
        for (int s : allowedSlots(def)) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }
}
