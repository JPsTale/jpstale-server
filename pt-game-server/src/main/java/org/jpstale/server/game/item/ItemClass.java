package org.jpstale.server.game.item;

/**
 * 物品大类（gamedb.itemlist.classItem，即原版 INVENTORY_POS 位值）集中定义。
 * <p>
 * classItem 是条目清单中的装备位标志（位值语义），与 C++ item.h 的
 * EItemType/weaponClass 不同——它是"该物品可穿到哪些装备槽/属于哪类" 的映射键。
 * <p>
 * DB 实测值：2副手/4单/6双手/8甲/16靴/32手/192戒/256宝石/512项链/2048护腕/
 * 8192药水/16384时装；classItem 0/1 无装备位（背包物）。
 */
public final class ItemClass {

    private ItemClass() {
    }

    // ---- classItem 位值 ----

    /** 副手：盾/法球/魔杖 */
    public static final int OFF_HAND = 2;
    /** 单手武器 */
    public static final int ONE_HAND_WEAPON = 4;
    /** 双手武器（占 SLOT_MAIN_HAND + SLOT_OFF_HAND） */
    public static final int TWO_HAND_WEAPON = 6;
    /** 铠甲/法袍 */
    public static final int ARMOR = 8;
    /** 靴子 */
    public static final int BOOTS = 16;
    /** 护手 */
    public static final int GLOVES = 32;
    /** 戒指（可 SLOT_RING_R / SLOT_RING_L） */
    public static final int RING = 192;
    /** 宝石 */
    public static final int GEM = 256;
    /** 项链 */
    public static final int AMULET = 512;
    /** 护腕 */
    public static final int ARMLET = 2048;
    /** 药水（快捷槽/负重按瓶数） */
    public static final int POTION = 8192;
    /** 时装（本轮不启用装备位） */
    public static final int COSTUME = 16384;

    // ---- 语义判定 ----

    /** 武器（单手或双手）——决定武器外观挂点/伤害。 */
    public static boolean isWeapon(int classItem) {
        return classItem == ONE_HAND_WEAPON || classItem == TWO_HAND_WEAPON;
    }

    /** 双手武器。 */
    public static boolean isTwoHandWeapon(int classItem) {
        return classItem == TWO_HAND_WEAPON;
    }

    /** 肢体防具系（甲/靴/手）——EquipSummary 防御/格挡/移速聚合用。 */
    public static boolean isBodyGear(int classItem) {
        return classItem == ARMOR || classItem == BOOTS || classItem == GLOVES;
    }

    /** 躯干外观（甲/法袍）——决定角色身体模型。 */
    public static boolean isTorsoArmor(int classItem) {
        return classItem == ARMOR;
    }

    /** 药水（负重按瓶数、地面物 TTL 低等）。 */
    public static boolean isPotion(int classItem) {
        return classItem == POTION;
    }
}