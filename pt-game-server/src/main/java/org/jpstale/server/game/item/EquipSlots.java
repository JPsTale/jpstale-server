package org.jpstale.server.game.item;

import org.jpstale.dao.gamedb.entity.ItemList;

/**
 * 物品 → 装备槽位映射（原版 CheckInvenItemPosition 语义）。
 * <p>
 * classItem 即 INVENTORY_POS 位值（DB 实测）：2副手/4单/6双手/8甲/16靴/32手/
 * 192戒/256宝石/512项链/2048护腕/8192药水/16384时装；classItem 0/1 无装备位（背包物）。
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
            case 2 -> new int[]{ItemLocations.SLOT_OFF_HAND};      // 盾/法球/魔杖
            case 4 -> new int[]{ItemLocations.SLOT_MAIN_HAND};     // 单手武器
            case 6 -> new int[]{ItemLocations.SLOT_MAIN_HAND};     // 双手武器（占 1+2）
            case 8 -> new int[]{ItemLocations.SLOT_ARMOR};         // 铠甲/法袍
            case 16 -> new int[]{ItemLocations.SLOT_BOOTS};        // 靴子
            case 32 -> new int[]{ItemLocations.SLOT_GLOVES};       // 护手
            case 192 -> new int[]{ItemLocations.SLOT_RING_R, ItemLocations.SLOT_RING_L};
            case 256 -> new int[]{ItemLocations.SLOT_SHELTOM};     // 宝石
            case 512 -> new int[]{ItemLocations.SLOT_AMULET};      // 项链
            case 2048 -> new int[]{ItemLocations.SLOT_ARMLET};     // 护腕
            case 8192 -> new int[]{ItemLocations.SLOT_POTION_1, ItemLocations.SLOT_POTION_2,
                    ItemLocations.SLOT_POTION_3};                  // 药水（可快捷槽）
            case 16384 -> new int[0];                              // 时装（本轮不启用）
            default -> new int[0];                                 // 0/1 消耗材料等 → 背包物
        };
    }

    /** 是否装备物（有可穿槽位）。 */
    public static boolean isEquipable(ItemList def) {
        return allowedSlots(def).length > 0;
    }

    /** 是否双手武器（classItem=6）。 */
    public static boolean isTwoHand(ItemList def) {
        return def != null && def.getClassItem() != null && def.getClassItem() == 6;
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
