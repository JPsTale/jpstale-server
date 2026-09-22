package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.AgeList;

/**
 * 锻造一次投石的**判定**（`gamedb.agelist` 曲线 + EU `GetAgingResultType` 的掷点顺序）。
 *
 * <p>
 * 逐字照抄 EU `Server/server/itemserver.cpp:2211-2305`：
 * <pre>
 *   iRandChance = 0..99;
 *   if (iRandChance &lt; iFailChance) {                       // 失败
 *       iChanceMode = 0..99;
 *       if (iChanceMode &lt; iMinus1Chance) return MinusOne;   // 先判 −1
 *       iChanceMode -= iMinus1Chance;
 *       if (iChanceMode &lt; iMinus2Chance) return MinusTwo;   // 再判 −2
 *       iChanceMode -= iMinus2Chance;
 *       if (iAgeStoneType == 2) return MinusOneCopperOre;    // **铜矿：失败不破坏，只 −1**
 *       if (bNoBreakEvent)      return MinusOneNoBreakEvent; // 活动保护
 *       return Destruction;                                  // 否则破坏
 *   } else {
 *       iChanceMode = 0..99;
 *       if (iChanceMode &lt; iPlus2Chance &amp;&amp; 等级+1 &lt;= AGING_MAX) return PlusTwo;   // 跳级（不越上限）
 *       return PlusOne;
 *   }
 * </pre>
 *
 * <p>
 * **`agestone` 列的真义**（EU 变量名 `iFailAgeStoneChance`，注释 "at the moment this is 0% !"）：
 * **用锻造石（Aging Stone）时的失败率** —— 我们库里 20 行全是 0 ⇒ **用石必成功**（只会 +1 或 +2）。
 *
 * <p>
 * 本类**纯函数**（随机数由调用方传入，便于测试与回放）；曲线行由 {@link AgeListService} 提供。
 */
public final class AgeRoll {

    private AgeRoll() {
    }

    /** 石头类型（EU `iAgeStoneType`：`itemserver.cpp:2207` 注释 "0 = none, 1 = aging stone, 2 = copper ore, 3 = max age"）。 */
    public static final int STONE_NONE = 0;
    public static final int STONE_AGING = 1;
    public static final int STONE_COPPER_ORE = 2;
    /** "max age" 类（一键拉满）—— 不走本判定，由调用方直接置满（EU `itemserver.cpp:2543`）。 */
    public static final int STONE_MAX_AGE = 3;

    /** 判定结果（EU `enum class AgingResultType`）。 */
    public enum Result {
        /** +1 级 */
        PLUS_ONE,
        /** +2 级（跳级） */
        PLUS_TWO,
        /** −1 级 */
        MINUS_ONE,
        /** −2 级 */
        MINUS_TWO,
        /** −1 级，且**因铜矿而未破坏** */
        MINUS_ONE_COPPER_ORE,
        /** −1 级，且因活动未破坏 */
        MINUS_ONE_NO_BREAK_EVENT,
        /** **破坏**（销毁物品） */
        DESTRUCTION;

        /** 是否破坏。 */
        public boolean broke() {
            return this == DESTRUCTION;
        }

        /** 等级变化量（成功为正）。 */
        public int levelDelta() {
            return switch (this) {
                case PLUS_ONE, MINUS_ONE, MINUS_ONE_COPPER_ORE, MINUS_ONE_NO_BREAK_EVENT -> this == PLUS_ONE ? 1 : -1;
                case PLUS_TWO -> 2;
                case MINUS_TWO -> -2;
                case DESTRUCTION -> 0;
            };
        }
    }

    /**
     * 掷一次。
     *
     * @param level        当前锻造等级（0 = 还没锻造过；对应 `agelist.agenumber = level + 1`）
     * @param stoneType    石头类型（{@link #STONE_NONE}/{@link #STONE_AGING}/{@link #STONE_COPPER_ORE}）
     * @param row          当前等级的曲线行（`agelist`）
     * @param ageMax       等级上限（我们定；EU `AGING_MAX = 20`）
     * @param noBreakEvent 活动保护（全局开关）
     * @param r1           0..99 的随机数（失败判定）
     * @param r2           0..99 的随机数（档位/成功判定）
     */
    public static Result roll(int level, int stoneType, AgeList row, int ageMax, boolean noBreakEvent,
                              int r1, int r2) {
        int failChance = failChanceOf(stoneType, row);
        int minus1 = nz(row.getMinus1Chance());
        int minus2 = nz(row.getMinus2Chance());
        int plus2 = nz(row.getPlus2Chance());
        int broken = nz(row.getBrokenChance());

        if (r1 < failChance) {
            // 失败：先 −1 → 再 −2 → 铜矿/活动保命 → 破坏
            if (r2 < minus1) {
                return Result.MINUS_ONE;
            }
            int mode = r2 - minus1;
            if (mode < minus2) {
                return Result.MINUS_TWO;
            }
            if (stoneType == STONE_COPPER_ORE) {
                return Result.MINUS_ONE_COPPER_ORE;
            }
            if (noBreakEvent) {
                return Result.MINUS_ONE_NO_BREAK_EVENT;
            }
            // ⚠ 破坏概率 `brokenchance` 在 EU 的实现里**没有被单独判**：剩下的这段就是破坏
            // （曲线里 brokenchance 与 minus1/minus2 的关系是"扣完两者剩下的比例"，见 §3.11.2）。
            // 我们把 `broken` 记进日志用（读出来给调用方看），判定本身与 EU 一致。
            return Result.DESTRUCTION;
        }
        // 成功：+2 需不越上限，否则降级为 +1。
        // ⚠ 判据是 EU 的 `iAgingLevel + 1 <= iAgeTotal`，而 EU 的 `iAgingLevel = sAgeLevel + 1`
        //   （`itemserver.cpp:2538`）⇒ 换成"我们的等级"就是 **level + 2 <= ageMax**
        //   （写成 level+1 会让 19 级跳到 21 级，越上限 —— 2026-09-22 单测抓到的真 bug）。
        if (r2 < plus2 && level + 2 <= ageMax) {
            return Result.PLUS_TWO;
        }
        return Result.PLUS_ONE;
    }

    /** 有效失败率：用锻造石时取 `agestone` 列（我们库里全 0 ⇒ 必成功），否则取 `failchance`。 */
    public static int failChanceOf(int stoneType, AgeList row) {
        return stoneType == STONE_AGING ? nz(row.getAgeStone()) : nz(row.getFailChance());
    }

    /** 该结果在曲线里对应的破坏概率（诊断用；判定本身不用它，见 {@link #roll} 的注释）。 */
    public static int brokenChanceOf(AgeList row) {
        return nz(row.getBrokenChance());
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
