package org.jpstale.server.game.item;

/**
 * PT 物品容器/槽位常量（画布版，设计文档 plans/item-inventory-system-design.md v3.2）
 * <p>
 * location 语义（userdb.item.location）：
 *   0=背包大画布  1=仓库画布  2=装备栏  6=备用武器槽
 *   3/4/5=预留（邮件/拍卖/交易锁定，本轮不启用）
 */
public final class ItemLocations {

    /** 背包大画布：12×12=144 格（原版两页 12×6 垂直拼接，无分面无 E 键） */
    public static final int BAG = 0;
    public static final int BAG_W = 12;
    public static final int BAG_H = 12;
    public static final int BAG_SLOTS = BAG_W * BAG_H;

    /** 仓库画布：9×9=81 格（源码实测 cWAREHOUSE::SetWareHouseItemAreaCheck 22*9） */
    public static final int WAREHOUSE = 1;
    public static final int WH_W = 9;
    public static final int WH_H = 9;
    public static final int WH_SLOTS = WH_W * WH_H;

    /** 装备栏：13 个有名槽（非画布） */
    public static final int EQUIP = 2;

    /** 备用武器槽（W 切换）：仅槽 1/2 有意义，UI 不可见 */
    public static final int BACKUP_WEAPON = 6;

    // ---- 装备槽位号（PT 权威 1~13，见 gameplay §5.0）----
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

    /** 该 location 的画布宽；非画布容器返回 0（装备栏/备用武器等按槽号）。 */
    public static int widthOf(int location) {
        return switch (location) {
            case BAG -> BAG_W;
            case WAREHOUSE -> WH_W;
            default -> 0;
        };
    }

    /** 该 location 的画布总格数；非画布返回 0。 */
    public static int slotsOf(int location) {
        return switch (location) {
            case BAG -> BAG_SLOTS;
            case WAREHOUSE -> WH_SLOTS;
            default -> 0;
        };
    }

    /** 判断 location 是否为画布容器（背包/仓库）。 */
    public static boolean isCanvas(int location) {
        return location == BAG || location == WAREHOUSE;
    }
}
