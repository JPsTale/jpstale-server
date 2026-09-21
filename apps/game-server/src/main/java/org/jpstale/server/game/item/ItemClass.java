package org.jpstale.server.game.item;

/**
 * classItem（`gamedb.itemlist.classItem`，代码里即原版 `Item.Class`）——**槽位位掩码**。
 *
 * 语义：**该物品能放进哪些槽位**的位集合（不是职业许可、不是堆叠标志、不是"装备大类"）。
 *
 * 权威定义：`NewSourcePT-2023/SrcGame/src/sinbaram/sinItem.h:16-32`（`INVENTORY_POS_*`），
 * 原版语义别名在同文件 `:35-46`（`ITEM_CLASS_WEAPON_ONE = RHAND`、
 * `ITEM_CLASS_WEAPON_TWO = RHAND|LHAND`、`ITEM_CLASS_POTION = POTION`）。
 * 另一用途是"位值 → 屏幕矩形"（`sinInvenTory.cpp:157+` 的槽位坐标表，药水槽三格在 `:167-169`）。
 *
 * ⚠ 客户端 `src/game/itemClass.ts` 是**同一份定义**（TS 侧）——改这里要同步改那边。
 */
public final class ItemClass {

    private ItemClass() {
    }

    // ================= 位值（与原版 sinItem.h:16-32 逐位一致，用十六进制便于看出是哪一位）=================

    /** 0x0001 背包盒（原版保留位，全仓无使用点） */
    public static final int BOX = 0x0001;
    /** 0x0002 左手（副手） */
    public static final int LHAND = 0x0002;
    /** 0x0004 右手（主手） */
    public static final int RHAND = 0x0004;
    /** 0x0008 铠甲/法袍 */
    public static final int ARMOR = 0x0008;
    /** 0x0010 靴子 */
    public static final int BOOTS = 0x0010;
    /** 0x0020 护手 */
    public static final int GLOVES = 0x0020;
    /** 0x0040 左戒指 */
    public static final int LRING = 0x0040;
    /** 0x0080 右戒指 */
    public static final int RRING = 0x0080;
    /** 0x0100 宝石（Sheltom） */
    public static final int SHELTOM = 0x0100;
    /** 0x0200 项链 */
    public static final int AMULET = 0x0200;
    /** 0x0800 护腕/臂环（命中 + 药水槽容量） */
    public static final int ARMLET = 0x0800;
    /** 0x1000 原版保留位（**不是**双手判据！全仓无使用点；双手见 {@link #WEAPON_TWO_HAND}） */
    public static final int TWO_HAND_FLAG = 0x1000;
    /** 0x2000 药水（快捷槽 ITEMSLOT 11/12/13，按瓶数堆叠） */
    public static final int POTION = 0x2000;
    /** 0x4000 时装 */
    public static final int COSTUME = 0x4000;
    /** 0x8000 右翼（原版保留，未启用） */
    public static final int WING_RIGHT = 0x8000;
    /** 0x10000 左耳环（原版保留，未启用） */
    public static final int EARRING_L = 0x10000;
    /** 0x20000 右耳环（原版保留，未启用） */
    public static final int EARRING_R = 0x20000;

    // ================= 语义别名（沿用原版 sinItem.h:35-46 的命名与组合方式）=================

    /** 副手：盾 / 法球 / 魔杖（= LHAND） */
    public static final int OFF_HAND = LHAND;
    /** 单手武器：只占右手（= RHAND，原版 `ITEM_CLASS_WEAPON_ONE`） */
    public static final int ONE_HAND_WEAPON = RHAND;
    /** 双手武器：右手 + 左手（= RHAND|LHAND，原版 `ITEM_CLASS_WEAPON_TWO`）；
     *  装备时副手必须空（两格同时占用）。 */
    public static final int TWO_HAND_WEAPON = RHAND | LHAND;
    /** 戒指（左或右） */
    public static final int RING = LRING | RRING;
    /** 宝石（= SHELTOM） */
    public static final int GEM = SHELTOM;

    // ================= 语义判定 =================

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

    /** 药水（负重按瓶数、药水槽按瓶数堆叠）。 */
    public static boolean isPotion(int classItem) {
        return classItem == POTION;
    }

    /**
     * 该 classItem 的物品**能否堆叠**（背包/药水槽里叠数量）。
     *
     * 判据：**无槽位位值**（0/1：材料/任务等）与**药水**（{@link #POTION}）可堆叠，其余（装备类）不可。
     * ⚠ 药水虽然**有**槽位位值，但那是**快捷槽**不是装备槽 —— 它必须能堆叠（药水槽就是按叠数量设计的）；
     * 曾漏判药水导致背包里无法合并（用户 2026-09-13 实测）。
     * 客户端同一判据在 `src/game/itemClass.ts:isStackable`。
     */
    public static boolean isStackable(int classItem) {
        return classItem == 0 || classItem == 1 || classItem == POTION;
    }
}
