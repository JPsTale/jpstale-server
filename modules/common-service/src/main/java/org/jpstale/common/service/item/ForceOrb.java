package org.jpstale.common.service.item;

/**
 * 力量石（Force Orb）的数值表 —— 逐字照抄 EU `Server/server/itemserver.cpp:55-110`。
 *
 * <pre>
 *   ForceDamageTable[]        = { 2,4,7,10,15,25,40,60,80,100,120,140,160,180 };               // 14 档（Lucidy→Oredo）
 *   ForceDurationTable[]      = { 500,500,500,500,700,900,1200,1400,1600,1800,2000,2400,3000,3600 };  // 秒
 *   ForceDamagePercentTable[] = { 0,0,0,0,0,0,10,10,10,10,10,10,10,10 };                       // 第 7 档（Murky）起 +10%
 * </pre>
 *
 * **应用顺序（EU `HNSSkill.cpp:1775-1784` 的注释逐字，比数值本身更重要）**：
 * <pre>
 *   // as we want to take the base attack power when doing computations
 *   // the flat addition is added at the end.
 *   iDamageMin += (iBaseAttackPowerMin * iValue) / 100;   // ★ 百分比基于**基础攻击力**
 *   iDamageMax += (iBaseAttackPowerMax * iValue) / 100;
 *   …最后才加 flat 的那份
 * </pre>
 * ⇒ 若顺序颠倒（flat 先进去再乘百分比），高等级会滚雪球 —— 见用户 2026-09-22 的担心
 * "直接追加攻击力在后期会十分无力"，正解就是"百分比 + flat" 且按此顺序。
 *
 * <p>
 * **档位判定**：`sinFO1 | (tier&lt;&lt;8)`（`sinFO1 = 0x03060000`），tier 1..14 对应 14 颗材料石
 * （Lucidy…Oredo）。EU 只启用前 12 档（`cnt &lt; 12`）与魔法石段（`20 &lt;= cnt &lt; 32`）；
 * **我们定**：14 档全开（数据里 14 颗都在，没理由浪费），已在实现方案 §4 记过。
 */
public final class ForceOrb {

    private ForceOrb() {
    }

    /** 力量石家族（`sinFO1`）。 */
    public static final int FAMILY = 0x03060000;

    /** 档数（= 我们的 14 颗石头）。 */
    public static final int TIERS = 14;

    /** 每次 buff 的**固定**攻击力加成（EU `ForceDamageTable`）。 */
    private static final int[] DAMAGE = {2, 4, 7, 10, 15, 25, 40, 60, 80, 100, 120, 140, 160, 180};

    /** 持续秒数（EU `ForceDurationTable`）。 */
    private static final int[] DURATION_SEC = {500, 500, 500, 500, 700, 900, 1200, 1400, 1600, 1800, 2000, 2400, 3000, 3600};

    /** 额外**百分比**攻击力（EU `ForceDamagePercentTable`；第 7 档起才是 10%）。 */
    private static final int[] PERCENT = {0, 0, 0, 0, 0, 0, 10, 10, 10, 10, 10, 10, 10, 10};

    /**
     * 力量石 idcode → 档位下标（0-based）；不是 `sinFO1` 族或超出 1..14 档返回 -1。
     */
    public static int tierIndexOf(Integer idCode) {
        if (idCode == null || (idCode & 0xFFFF0000) != FAMILY) {
            return -1;
        }
        int tier = (idCode & 0xFFFF) >>> 8;
        return (tier >= 1 && tier <= TIERS) ? tier - 1 : -1;
    }

    /** 该档的固定攻击力加成。 */
    public static int flatDamage(int index) {
        return DAMAGE[Math.max(0, Math.min(TIERS - 1, index))];
    }

    /** 该档的百分比攻击力加成（0 = 无）。 */
    public static int percentDamage(int index) {
        return PERCENT[Math.max(0, Math.min(TIERS - 1, index))];
    }

    /** 该档的持续时长（毫秒）。 */
    public static long durationMs(int index) {
        return DURATION_SEC[Math.max(0, Math.min(TIERS - 1, index))] * 1000L;
    }

    /** 该档的持续秒数（面板/日志用）。 */
    public static int durationSec(int index) {
        return DURATION_SEC[Math.max(0, Math.min(TIERS - 1, index))];
    }
}
