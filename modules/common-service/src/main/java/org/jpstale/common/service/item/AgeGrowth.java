package org.jpstale.common.service.item;

/**
 * **锻造升级时属性怎么涨** —— 逐字照抄 EU `CAgeHandler::OnUpAge`
 * （`PristonTale-EU-main/Server/server/AgeHandler.cpp:404`）。
 *
 * <p>⚠ 与"烘焙"写法的区别（2026-09-22 重构）：这里写的是 {@link ItemInstance#setEffective}
 * （= 记**加成**增量），**不动数据库字段**。因为属性是"读时按锻造等级 N 重算"的
 * ⇒ 掉级只要用 N−1 重算一次就精确回到原值 —— 不需要原版那套 `DownDamage/DownCritical/DownDefense`
 * 反向减法（那套必须把**原等级**传进去、还要处理取整，见 `ItemStat` 的类注释）。
 * 读值用 `effective(...)`（基准 + 已有加成）⇒ "防御 +5%" 这种按**当前值**的成长才能逐级累加正确。
 *
 * <p>按类型的每级增长（EU 原函数的 case 分派）：
 * <pre>
 *   斧   WA：伤害 +1/+1，命中 +10
 *   锤   WH：伤害 +1/+1，命中 +8，必杀（隔级）
 *   镰/匕首/剑/爪 WP/WD/WS2/WC（以及我们按爪处理的拳套 WV）：伤害 +1/+1，命中 +5，必杀（隔级）
 *   杖/图腾 WM/WN：伤害 +1/+1，命中 +8，必杀（隔级），灵力 +10
 *   弓/标枪 WS1/WT：伤害 +1/+1，必杀（隔级）
 *   盾/法球 DS1/OM1：防御 +5%（按当前值），格挡（隔级）+1，吸收 +0.4
 *   甲/法袍 DA*：防御 +5%（按当前值），吸收 +0.5
 *   其余（靴/护手/护腕/戒指…）：**不涨** —— EU 的 switch 没有这些 case，调用方可用 {@link #grows} 先判
 * </pre>
 * 等级 ≥ 9 时伤害与吸收各再翻一倍（原版 `if (levelBefore >= 9)` 分支）；
 * 必杀/格挡判的是"**旧等级**为奇数"（原版在 `sAgeLevel++` **之前**调用）。
 */
public final class AgeGrowth {

    private AgeGrowth() {
    }

    /** 该家族会不会因锻造涨属性（靴/护手/护腕/戒指等返回 false）。 */
    public static boolean grows(int idCode) {
        return switch (idCode & 0xFFFF0000) {
            case 0x01010000, // 斧 WA
                 0x01020000, // 爪 WC
                 0x01030000, // 锤 WH
                 0x01040000, // 法杖 WM
                 0x01050000, // 矛/镰 WP
                 0x01060000, // 弓 WS1
                 0x01070000, // 剑 WS2
                 0x01080000, // 标枪 WT
                 0x01090000, // 图腾 WN
                 0x010A0000, // 匕首 WD
                 0x010B0000, // 拳套 WV（我们补的新族）
                 0x02010000, 0x02050000, 0x02120000, 0x02130000, // 甲 DA1/DA2/DA3/DA4
                 0x02040000, // 盾 DS1
                 0x03030000 -> true;  // 法球 OM1
            default -> false;
        };
    }

    /**
     * 把"第 {@code levelBefore} 级（旧等级）对应的增长"累加进**加成**。
     *
     * @param it          目标装备（读 {@code effective}、写 {@code setEffective}）
     * @param levelBefore 这次升级**之前**的锻造等级（0 = 从 +0 升到 +1）
     */
    public static void apply(ItemInstance it, int levelBefore) {
        Integer code = it.getItemCode();
        if (code == null) {
            return;
        }
        switch (code & 0xFFFF0000) {
            case 0x01010000 -> {                       // 斧
                upDamage(it, levelBefore);
                addFlat(it, ItemStat.ATTACK_RATING, 10);
            }
            case 0x01030000 -> {                       // 锤
                upDamage(it, levelBefore);
                addFlat(it, ItemStat.ATTACK_RATING, 8);
                upCritical(it, levelBefore);
            }
            // 镰/匕首/剑/爪 —— 以及 拳套（WV 按爪）
            case 0x01050000, 0x010A0000, 0x01070000, 0x01020000, 0x010B0000 -> {
                upDamage(it, levelBefore);
                addFlat(it, ItemStat.ATTACK_RATING, 5);
                upCritical(it, levelBefore);
            }
            case 0x01040000, 0x01090000 -> {           // 法杖 / 图腾
                upDamage(it, levelBefore);
                addFlat(it, ItemStat.ATTACK_RATING, 8);
                upCritical(it, levelBefore);
                addFlat(it, ItemStat.INCREASE_MANA, 10);
            }
            case 0x01060000, 0x01080000 -> {           // 弓 / 标枪
                upDamage(it, levelBefore);
                upCritical(it, levelBefore);
            }
            case 0x02040000, 0x03030000 -> {           // 盾 / 法球
                upDefense(it, 5);
                upBlock(it, levelBefore);
                upAbsorb(it, 0.4, levelBefore);
            }
            case 0x02010000, 0x02050000, 0x02120000, 0x02130000 -> {  // 甲 / 法袍
                upDefense(it, 5);
                upAbsorb(it, 0.5, levelBefore);
            }
            default -> {
                // 靴/护手/护腕/戒指/项链…：EU 的 switch 没有这些 case ⇒ 不涨属性
                //（不静默假装：调用方可用 grows() 先判，日志里也照实写）。
            }
        }
    }

    private static void upDamage(ItemInstance it, int levelBefore) {
        double min = it.effective(ItemStat.DAMAGE_MIN) + 1;
        double max = it.effective(ItemStat.DAMAGE_MAX) + 1;
        if (levelBefore >= 9) {
            min += 1;
            max += 1;
        }
        it.setEffective(ItemStat.DAMAGE_MIN, min);
        it.setEffective(ItemStat.DAMAGE_MAX, max);
    }

    /** 防御按**当前生效值**的百分比涨（`Math.round` 与原版 `round` 同取整）。 */
    private static void upDefense(ItemInstance it, int percent) {
        double cur = it.effective(ItemStat.DEFENCE);
        it.setEffective(ItemStat.DEFENCE, cur + Math.round(cur / (100.0 / percent)));
    }

    /** 吸收每级 +percent；**旧等级 ≥ 9 时该级再加一次**（原版 `UpAbsorbRating` 的 `if (levelBefore >= 9)`）。 */
    private static void upAbsorb(ItemInstance it, double percent, int levelBefore) {
        double add = levelBefore >= 9 ? percent * 2 : percent;
        it.setEffective(ItemStat.ABSORB, it.effective(ItemStat.ABSORB) + add);
    }

    /** 必杀"每两级 +1"：判**旧等级**为奇数（原版 `UpCritical` 在 `sAgeLevel++` 之前调用）。 */
    private static void upCritical(ItemInstance it, int levelBefore) {
        if (levelBefore > 0 && (levelBefore % 2) == 1) {
            addFlat(it, ItemStat.CRITICAL, 1);
        }
    }

    private static void upBlock(ItemInstance it, int levelBefore) {
        if (levelBefore > 0 && (levelBefore % 2) == 1) {
            it.setEffective(ItemStat.BLOCK_RATING, it.effective(ItemStat.BLOCK_RATING) + 1.0);
        }
    }

    /** 直接加一个数（读当前生效值再写回加成）。 */
    private static void addFlat(ItemInstance it, ItemStat stat, double v) {
        it.setEffective(stat, it.effective(stat) + v);
    }
}
