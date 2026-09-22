package org.jpstale.common.service.item;

/**
 * `userdb.item.kind_code` 的取值 —— 照抄原版 `ITEM_KIND_*`（`sinItem.h:301-309`），
 * 与 EU 的 `EItemCraftType`（`shared/item.h:1002-1010`）**数值一致且注释互指**：
 * <pre>
 *   0 ITEM_KIND_NORMAL        = ITEMCRAFTTYPE_None
 *   1 ITEM_KIND_CRAFT         = ITEMCRAFTTYPE_Mixing   ← 合成物
 *   2 ITEM_KIND_AGING         = ITEMCRAFTTYPE_Aging    ← 锻造物
 *   3 ITEM_KIND_QUEST         = ITEMCRAFTTYPE_Quest
 *   4 ITEM_KIND_EVENT         = ITEMCRAFTTYPE_Event
 *   5 ITEM_KIND_MAKE_MAIN     = ITEMCRAFTTYPE_Bottle
 *   6 ITEM_KIND_MAKE_ELEMENT
 *   7 ITEM_KIND_QUEST_WEAPON
 *   8 ITEM_KIND_SPECIAL
 * </pre>
 * 客户端信息框按它区分"合成物/锻造物"（原版 `sinItem.cpp:754` 的 `if (sInfo.ItemKindCode == 1)` 分支）。
 */
public final class ItemKind {

    private ItemKind() {
    }

    public static final int NORMAL = 0;
    /** 合成物。 */
    public static final int CRAFT = 1;
    /** **锻造物** —— 投石/战斗养升级后写进 `kind_code`。 */
    public static final int AGING = 2;
    public static final int QUEST = 3;
    public static final int EVENT = 4;
    public static final int MAKE_MAIN = 5;
    public static final int MAKE_ELEMENT = 6;
    public static final int QUEST_WEAPON = 7;
    public static final int SPECIAL = 8;

    /** 是否合成物（客户端显示配方名/配色靠它）。 */
    public static boolean isCraft(int kindCode) {
        return kindCode == CRAFT;
    }

    /** 是否锻造物（客户端显示 `+N` 与强化行配色靠它）。 */
    public static boolean isAging(int kindCode) {
        return kindCode == AGING;
    }
}
