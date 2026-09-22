package org.jpstale.common.service.item;

/**
 * **锻造熟练度（原版叫 Mature bar）的每级阈值** —— 即"战斗养一次锻造，需要攒多少进度"。
 *
 * <p>来源：ex-machina `src/game/Legacy/Game/Interface/sinTrade.cpp` 的 `_W_SERVER` 分支
 * （`:197-205`，逐字抄；同文件另有两套被 `#ifdef` 排除的变体：`__SIN_AGING_TEST` 是调试用的小数值、
 * `#else` 是另一套（40/80/160…），**服务端用 `_W_SERVER` 这套**）。
 * 运行时按**家族**选表、按**当前锻造等级**取值（源码是 `ItemAgingCount[1] = AgingLevelXxx[ItemAgingNum[0]]`，
 * 即"需要的进度"= 表[当前等级]），`ItemAgingCount[0]` 是当前进度 ⇒ 这正是我们实例上的
 * {@code aging_exp} / {@code aging_exp_max}（EU 里叫 `sMatureBar` 的 `sCur`/`sMax`，见 `shared/item.h:1660`）。
 *
 * <p><b>家族 → 表的映射</b>（照抄同文件 `:5108-5165` 的四条 if）：
 * <ul>
 *   <li>{@code AgingLevelCritical}：爪 `sinWC1` / 剑 `sinWS2` / 镰·矛 `sinWP1`</li>
 *   <li>{@code AgingLevelAttack}：斧 `sinWA1` / 锤 `sinWH1` / 杖 `sinWM1` / 弓 `sinWS1` / 标枪 `sinWT1`</li>
 *   <li>{@code AgingLevelBlock}：盾 `sinDS1`</li>
 *   <li>{@code AgingLevelHit}：甲 `sinDA1` / 法袍 `sinDA2` / 法球 `sinOM1` / 靴 `sinDB1` / 护手 `sinDG1` / 护腕 `sinOA2`</li>
 *   <li>其余 → 固定 {@code 60 * 20 = 1200}（源码的 else 支）</li>
 * </ul>
 *
 * <p>⚠ <b>三族由我们定</b>（ex-machina 的这张 switch 里没有它们 —— 它是 8 职业时代的东西，
 * 那时没有刺客/萨满/格斗家，属"那个时代还没有"，不是"设计上走默认值"，见 AGENTS 纠错 #13）。
 * 定法与该族的 {@link AgeGrowth} 增长同源（EU `CAgeHandler::OnUpAge` 的分支）：
 * <ul>
 *   <li>匕首 `WD`(0x010A) → {@code Critical}（EU 把 `ITEMTYPE_Dagger` 与剑/爪同支）</li>
 *   <li>拳套 `WV`(0x010B) → {@code Critical}（我们已定"拳套按爪"）</li>
 *   <li>图腾 `WN`(0x0109) → {@code Attack}（EU 把 `ITEMTYPE_Phantom` 与 `Wand` 同支）</li>
 * </ul>
 */
public final class MatureProgress {

    private MatureProgress() {
    }

    /** 斧/锤/杖/弓/标枪（`sinWA1/sinWH1/sinWM1/sinWS1/sinWT1`）—— 20 项 = 等级 0..19 */
    private static final int[] ATTACK = {
            100, 130, 169, 219, 284, 369, 479, 622, 808, 1049,
            1362, 1769, 2297, 2983, 3874, 5031, 6534, 8486, 11021, 14313,
    };
    /** 爪/剑/镰·矛（`sinWC1/sinWS2/sinWP1`）+ 我们按同源补的匕首/拳套 */
    private static final int[] CRITICAL = {
            12, 16, 21, 27, 35, 45, 58, 75, 97, 126,
            164, 213, 277, 360, 468, 608, 790, 1026, 1332, 1730,
    };
    /** 盾（`sinDS1`） */
    private static final int[] BLOCK = {
            15, 19, 25, 32, 42, 55, 71, 92, 119, 155,
            201, 261, 339, 440, 571, 742, 964, 1252, 1626, 2112,
    };
    /** 甲/法袍/法球/靴/护手/护腕（`sinDA1/sinDA2/sinOM1/sinDB1/sinDG1/sinOA2`） */
    private static final int[] HIT = {
            45, 58, 75, 97, 126, 164, 213, 277, 360, 468,
            608, 790, 1026, 1332, 1730, 2247, 2918, 3790, 4922, 6392,
    };
    /** 未列出的家族（源码 else 支：`60 * 20`） */
    private static final int DEFAULT = 60 * 20;

    /**
     * 该装备在**当前锻造等级**下，战斗养满一次所需进度。
     *
     * @param idCode 物品码（家族取高 16 位）
     * @param level  当前锻造等级（0 = 未锻造）
     * @return 需要的进度；等级超出表长（≥20，实际到不了 —— 曲线只到 20 级）时夹到表尾
     */
    public static int needFor(Integer idCode, int level) {
        if (idCode == null || level < 0) {
            return DEFAULT;
        }
        int[] table = tableOf(idCode);
        if (table == null) {
            return DEFAULT;
        }
        return table[Math.min(level, table.length - 1)];
    }

    /** 家族 → 阈值表（不可合成的家族返回 null = 走默认值）。 */
    private static int[] tableOf(int idCode) {
        return switch (idCode & 0xFFFF0000) {
            // Critical：爪 / 剑 / 镰·矛（+ 我们的匕首 WD、拳套 WV —— 见类注释）
            case 0x01020000, 0x01070000, 0x01050000, 0x010A0000, 0x010B0000 -> CRITICAL;
            // Attack：斧 / 锤 / 杖 / 弓 / 标枪（+ 我们的图腾 WN —— EU 里 Phantom 与 Wand 同支）
            case 0x01010000, 0x01030000, 0x01040000, 0x01060000, 0x01080000, 0x01090000 -> ATTACK;
            // Block：盾
            case 0x02040000 -> BLOCK;
            // Hit：甲 / 法袍 / 法球 / 靴 / 护手 / 护腕
            case 0x02010000, 0x02050000, 0x03030000, 0x02020000, 0x02030000, 0x03020000 -> HIT;
            default -> null;
        };
    }
}
