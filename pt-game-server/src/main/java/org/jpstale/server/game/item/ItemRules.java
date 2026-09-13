package org.jpstale.server.game.item;

/**
 * 物品使用规则（**原版代码里的表**，不是我们发明的）。
 *
 * 权威出处：`NewSourcePT-2023/SrcGame/src/sinbaram/sinItem.cpp:64-72` 定义了**三张同源的姊妹表**：
 * <pre>
 *   NotSell_Item_{CODE,MASK,KIND}   // 不能卖给 NPC
 *   NotDrow_Item_{CODE,MASK,KIND}   // 不能丢到地面
 *   NotSet_Item_ {CODE,MASK,KIND}   // 不能摆摊/放进商店
 *   NotDrow_Item_CODE[] = { (sinQT1|sin07), (sinQT1|sin08), 0 };   // 0x07010007 / 0x07010008
 *   NotDrow_Item_MASK[] = { 0 };                                   // 家族掩码表为空
 *   NotDrow_Item_KIND[] = { ITEM_KIND_QUEST_WEAPON, 0 };           // 按 ItemKindCode：任务武器
 *   sinQT1 = 0x07010000
 * </pre>
 * 命中判据（`sinInvenTory1.cpp:5849-5862`）：三张表**任一命中就不许**。
 *
 * ⚠ **我们的差异**：第三张表按 `ItemKindCode` 判，而我们的 `gamedb.itemlist` **没有这一列**
 * → 用**任务家族**（`idCode & 0xFFFF0000 == 0x07010000`）近似。它比"具体两码"更宽，
 * 取舍是**宁可不让丢，也不误丢任务物品**（丢出去不可逆）。
 *
 * 客户端同一份表在 `src/game/itemRules.ts`（改一边要改另一边）。
 */
public final class ItemRules {

    private ItemRules() {
    }

    /** 原版 `sinITEM_MASK2`：idcode 的高 16 位（家族） */
    private static final int MASK2 = 0xFFFF0000;
    /** 原版 `sinQT1`：任务物品家族 */
    private static final int FAMILY_QUEST = 0x07010000;

    /** `NotDrow_Item_CODE[]`：不能丢到地上的**具体码** */
    private static final int[] NOT_DROP_CODES = { 0x07010007, 0x07010008 };
    /** `NotSell_Item_CODE[]`：不能卖给 NPC（原版与禁丢同表） */
    private static final int[] NOT_SELL_CODES = { 0x07010007, 0x07010008 };

    private static boolean inFamily(int idCode) {
        return (idCode & MASK2) == FAMILY_QUEST;
    }

    /** 能否丢到地面（原版 `NotDrow_Item_*`）。 */
    public static boolean isDroppable(int idCode) {
        if (idCode == 0) {
            return true;
        }
        for (int c : NOT_DROP_CODES) {
            if (c == idCode) {
                return false;
            }
        }
        return !inFamily(idCode);   // 任务家族一律不许丢（原版还含 ITEM_KIND_QUEST_WEAPON，我们缺该列）
    }

    /** 能否卖给 NPC（原版 `NotSell_Item_*`；目前尚无出售入口，先备好判据）。 */
    public static boolean isSellable(int idCode) {
        if (idCode == 0) {
            return true;
        }
        for (int c : NOT_SELL_CODES) {
            if (c == idCode) {
                return false;
            }
        }
        return !inFamily(idCode);
    }

    // ================= 装备职业门（原版 `NotUseFlag` 的真正语义）=================
    //
    // 出处：`NewSourcePT-2023/SrcGame/src/sinbaram/sinInvenTory1.cpp:6152-6206` —— 原版客户端在
    // 放装备时按下面这些**硬编码规则**置 `NotUseFlag`，命中就不许放进槽（`CheckSetOk` 里
    // `ItemPosition != 0 && NotUseFlag` → `MESSAGE_NO_USE_ITEM` 并拒绝）。
    // 家族码取自 `sinItem.h:68-88`。客户端同一份在 `src/game/itemRules.ts`。
    //
    // ⚠ 更权威的是服务端 OpenItem 的 `**특화`/`**특화랜덤` 字段（AGENTS 纠错 #8），
    //   但 `items-11job.json` 当初没保留那两列；在重扫之前，这里用**与原版客户端一致**的规则兜住。

    private static final int WA1 = 0x01010000;   // 斧
    private static final int WC1 = 0x01020000;   // 爪
    private static final int WH1 = 0x01030000;   // 锤
    private static final int WM1 = 0x01040000;   // 法杖
    private static final int WP1 = 0x01050000;   // 枪
    private static final int WS1 = 0x01060000;   // 弓
    private static final int WS2 = 0x01070000;   // 剑
    private static final int WT1 = 0x01080000;   // 标枪
    private static final int WN1 = 0x01090000;   // 图腾（萨满）
    private static final int WD1 = 0x010A0000;   // 匕首（刺客）
    private static final int DA1 = 0x02010000;   // 铠甲
    private static final int DA2 = 0x02050000;   // 法袍
    private static final int OM1 = 0x03030000;   // 法球

    /**
     * 该职业能否使用这件装备 —— **逐条照抄原版客户端的判定**（顺序与条件一一对应）。
     *
     * @param job   角色职业号（1..11；7 法师 / 8 祭司 / 9 刺客 / 10 萨满）
     * @param idCode 物品 idcode
     */
    public static boolean canUse(int job, int idCode) {
        if (idCode == 0) {
            return true;
        }
        int f = idCode & MASK2;
        // 法系（法师/祭司/萨满）穿不了铠甲；非魔法职业穿不了法袍、用不了法球
        boolean magicJob = job == 7 || job == 8 || job == 10;
        if (magicJob ? (f == DA1) : (f == DA2 || f == OM1)) {
            return false;
        }
        // 刺客用不了这些武器
        if (job == 9 && (f == WA1 || f == WH1 || f == WT1 || f == WP1
                || f == WS1 || f == WS2 || f == WM1 || f == WN1)) {
            return false;
        }
        // 萨满用不了这些武器
        if (job == 10 && (f == WA1 || f == WC1 || f == WH1 || f == WT1 || f == WP1
                || f == WS1 || f == WS2 || f == WM1 || f == WD1)) {
            return false;
        }
        // 匕首只有刺客、图腾只有萨满
        if (f == WD1 && job != 9) {
            return false;
        }
        if (f == WN1 && job != 10) {
            return false;
        }
        return true;
    }
}
