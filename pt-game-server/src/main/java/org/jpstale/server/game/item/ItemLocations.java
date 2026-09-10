package org.jpstale.server.game.item;

/**
 * PT 物品容器/槽位常量（十进制分段编码，design-背包装备系统 v0.2）
 * <p>
 * location 语义（userdb.item.location，十进制分段）：
 *   0=装备栏   1=副装备栏   10~19=背包页   20=任务栏   30~39=仓库页   40=商店（拍卖行）
 *   v1 只启用：0 装备栏 / 1 副装备栏 / 10 背包页1 / 30 仓库页1
 */
public final class ItemLocations {

    // ---- location 十进制段 ----

    /** 装备栏：13 个有名槽（非画布），slot 1~13 命名槽 */
    public static final int EQUIP = 0;

    /** 副装备栏（第二套武器）：slot 与装备栏一致（1=主手、2=副手）。
     *  不计负重，W 切换激活后才计入（用户拍板：让玩家更爽）。 */
    public static final int BACKUP_EQUIP = 1;

    /** 背包页基准：10~19（十进制个位=页号）。v1 只启用第 1 页。 */
    public static final int BAG_PAGE_BASE = 10;
    /** 背包页1（v1 启用）：12×12=144，slot 0~143 */
    public static final int BAG_PAGE = BAG_PAGE_BASE;
    public static final int BAG_W = 12;
    public static final int BAG_H = 12;
    public static final int BAG_SLOTS = BAG_W * BAG_H;

    /** 任务物品栏：v1 预留不启用 */
    public static final int QUEST = 20;

    /** 仓库页基准：30~39（十进制个位=页号）。v1 只启用第 1 页。 */
    public static final int WAREHOUSE_PAGE_BASE = 30;
    /** 仓库页1（v1 启用）：9×9=81，slot 0~80 */
    public static final int WAREHOUSE = WAREHOUSE_PAGE_BASE;
    public static final int WH_W = 9;
    public static final int WH_H = 9;
    public static final int WH_SLOTS = WH_W * WH_H;

    /** 个人商店（拍卖行）：v1 预留不启用 */
    public static final int STORE = 40;

    // ---- 装备槽位号（PT 权威 1~13，见 gameplay §5.0；装备栏与副装备栏共用）----
    public static final int SLOT_MAIN_HAND = 1;    // 右手主武器（含双手武器）
    public static final int SLOT_OFF_HAND = 2;     // 左手（盾/法球/双手副手）
    public static final int SLOT_ARMOR = 3;        // 铠甲/法袍
    public static final int SLOT_AMULET = 4;       // 项链
    public static final int SLOT_RING_R = 5;       // 右戒指
    public static final int SLOT_RING_L = 6;       // 左戒指
    public static final int SLOT_SHELTOM = 7;      // 宝石
    public static final int SLOT_ARMLET = 8;       // 护腕（命中+药水槽容量）
    public static final int SLOT_GLOVES = 9;       // 护手
    public static final int SLOT_BOOTS = 10;       // 靴子
    public static final int SLOT_POTION_1 = 11;    // 药水槽1
    public static final int SLOT_POTION_2 = 12;    // 药水槽2
    public static final int SLOT_POTION_3 = 13;    // 药水槽3

    private ItemLocations() {
    }

    /** 该 location 的画布宽；非画布容器返回 0（装备栏/副装备栏按槽号）。 */
    public static int widthOf(int location) {
        return switch (location) {
            case BAG_PAGE -> BAG_W;
            case WAREHOUSE -> WH_W;
            default -> 0;
        };
    }

    /** 该 location 的画布总格数；非画布返回 0。 */
    public static int slotsOf(int location) {
        return switch (location) {
            case BAG_PAGE -> BAG_SLOTS;
            case WAREHOUSE -> WH_SLOTS;
            default -> 0;
        };
    }

    /** 判断 location 是否为画布容器（背包页 / 仓库页）。 */
    public static boolean isCanvas(int location) {
        return location == BAG_PAGE || location == WAREHOUSE;
    }

    /** 判断是否为背包页（10~19 段）。 */
    public static boolean isBagPage(int location) {
        return location >= BAG_PAGE_BASE && location < BAG_PAGE_BASE + 10;
    }

    /** 判断是否为仓库页（30~39 段）。 */
    public static boolean isWarehousePage(int location) {
        return location >= WAREHOUSE_PAGE_BASE && location < WAREHOUSE_PAGE_BASE + 10;
    }

    /** 向前兼容别名：背包第 1 页（原 BAG 语义）。 */
    public static final int BAG = BAG_PAGE;

    /** 向前兼容别名：备用武器槽（原 BACKUP_WEAPON 语义）。 */
    public static final int BACKUP_WEAPON = BACKUP_EQUIP;
}