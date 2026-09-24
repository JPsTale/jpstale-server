package org.jpstale.common.service.stat;

import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.MonsterStats;
import org.jpstale.common.service.model.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DamageCalculator#calculateMonsterToMonster} 的特征测试 —— 召唤物打怪 / 怪打召唤物。
 *
 * <p>
 * 它在怪→怪这条链上，与 {@code calculateMonsterToPlayer} 的差别只有两处，而这两处**错了不会报错、
 * 只是数值悄悄不对**，所以逐条钉住：
 * <ol>
 *   <li>吸收是**百分比**（原版 `pw -= (pow * Absorption) / 100`），不是玩家那种明文减伤点数；</li>
 *   <li>**没有格挡**（怪不会格挡 —— 那是玩家专有）。</li>
 * </ol>
 */
class DamageCalculatorTest {

    private DamageCalculator calculator;

    @BeforeEach
    void setUp() throws Exception {
        // 本项目没有 spring-boot-starter-test（只有 junit-jupiter），注入只能自己做。
        // 按仓库惯例用**真实类**而不是 mock：PlayerStatCalculator 是无依赖的纯计算器。
        calculator = new DamageCalculator();
        Field f = DamageCalculator.class.getDeclaredField("statCalculator");
        f.setAccessible(true);
        f.set(calculator, new PlayerStatCalculator());
    }

    /** 攻击力区间取成上下界相等 ⇒ 基础伤害确定（`nextInt(max(1,0)) + min + 1` = min+1），便于精确断言。 */
    private static MonsterStats attacker(int atkMin, int atkMax, int attackRating) {
        return new MonsterStats(50, 0, 0, 1000, attackRating, atkMin, atkMax);
    }

    private static MonsterStats defender(int absorption) {
        return new MonsterStats(50, 0, absorption, 1000, 0, 1, 1);
    }

    /** 命中率封顶 95%（`monsterAccuracyPvp` 的 clamp），故循环采样；只断言"每次结果都合规"。 */
    private DamageResult firstHit(MonsterStats atk, MonsterStats def) {
        for (int i = 0; i < 2000; i++) {
            DamageResult r = calculator.calculateMonsterToMonster(atk, def);
            if (!r.isMissed()) {
                return r;
            }
        }
        return fail("2000 次都没命中 —— 命中率封顶 95%，这不可能，说明命中判定坏了");
    }

    @Test
    void 无吸收时命中伤害等于攻方攻击力区间掷点() {
        // atkMin = atkMax = 100 ⇒ 基础 = 100 + 1 = 101（与原版一致：下一行是 `atkMin + 1`）
        assertEquals(101, firstHit(attacker(100, 100, 9999), defender(0)).getFinalDamage());
    }

    @Test
    void 吸收是百分比而不是明文点数() {
        // 101 × (100-10)/100 = 90（整数除法）；若照玩家那套明文减伤点数写，这里会是 91
        assertEquals(90, firstHit(attacker(100, 100, 9999), defender(10)).getFinalDamage(),
            "吸收必须按百分比 —— 90 说明是百分比，91 说明被写成了明文减伤");
        assertEquals(50, firstHit(attacker(100, 100, 9999), defender(50)).getFinalDamage());
    }

    @Test
    void 全吸收也至少掉一点血() {
        assertEquals(1, firstHit(attacker(100, 100, 9999), defender(100)).getFinalDamage(),
            "吸收 100% 时兜底最小伤害 1（打中就是掉血）");
    }

    @Test
    void 未命中时没有伤害() {
        MonsterStats weak = attacker(1, 1, 0);   // rating 0 对高防 → 命中率被夹到下限 30%
        MonsterStats tough = new MonsterStats(99, 9999, 0, 1000, 0, 1, 1);
        boolean sawMiss = false;
        for (int i = 0; i < 2000 && !sawMiss; i++) {
            DamageResult r = calculator.calculateMonsterToMonster(weak, tough);
            if (r.isMissed()) {
                sawMiss = true;
                assertEquals(0, r.getFinalDamage(), "未命中必须是 0 伤害");
                assertFalse(r.isBlocked(), "怪→怪没有格挡这一档");
            }
        }
        assertTrue(sawMiss, "命中率最低 30%（不是 100%），2000 次里必须出现未命中");
    }

    @Test
    void 命中判定确实在掷点_命中率落在合理区间() {
        // rating 极高 / defense 0 ⇒ `monsterAccuracyPvp` 的命中率封顶 95%（它把结果 clamp 到 [30,95]）。
        // 这里只能按**统计**断言：95% 是期望值，n=2000 时标准差 σ=√(0.95·0.05/2000)≈0.49%，
        // 所以 [0.92, 0.98] 约等于 ±6σ —— 越界概率可忽略，而区间又窄到能证明"真的在掷点、
        // 且封顶在 95%（不是恒中）"。⚠ 别把这个区间收紧回 0.95：那会变成概率性红灯。
        int hits = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            if (!calculator.calculateMonsterToMonster(attacker(100, 100, 99999), defender(0)).isMissed()) {
                hits++;
            }
        }
        double rate = hits / (double) n;
        assertTrue(rate > 0.92 && rate < 0.98,
            "命中率应在 95% 附近（封顶 95%，n=2000 的统计噪声 σ≈0.5%），实测 " + rate);
        assertTrue(hits > 0 && hits < n, "两种结果都必须出现过 —— 否则说明命中判定被跳过了");
    }

    @Test
    void 攻击力上界不大于下界时不抛异常() {
        // 坏数据（max < min）：`nextInt(负数)` 会抛 IllegalArgumentException。
        // 数据问题不该表现成服务端崩溃 —— 兜到"打一下"即可。
        assertDoesNotThrow(() -> firstHit(attacker(100, 50, 9999), defender(0)));
        assertEquals(101, firstHit(attacker(100, 50, 9999), defender(0)).getFinalDamage());
    }

    /**
     * **必中**入口（`calculatePlayerToMonsterAlwaysHit`）：Pike Wind 等"必中"技能的伤害路径。
     *
     * 依据：原版 `dm_SelectRange(x, y, z, range, FALSE)` ⇒ `dmUseAccuracy = 0`（`Damage.cpp:428/454`），
     * Pike Wind 的选敌就是那一支 —— **不做命中判定**（这是"必中"的来源，不是我们的创设）。
     * 用户 2026-09-24 实测"Pike Wind 会 MISS"⇒ 本测试钉住"永不 miss"。
     *
     * 做法：把玩家的命中率压到最低（高等级高防御的怪），掷普通入口必然大量 MISS，
     * 必中入口**一次都不该 MISS**。
     */
    @Test
    void 必中入口永不miss() {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setJob(4);
        p.setLevel(1);
        p.setStrength(20);
        p.setSpirit(10);
        p.setTalent(10);
        p.setAgility(10);
        p.setHealth(10);
        // 高等级 + 高防御的怪：普通命中率被压到很低
        MonsterStats tank = new MonsterStats(90, 9999, 0, 100000, 0, 1, 1);

        int normalMiss = 0;
        for (int i = 0; i < 200; i++) {
            if (calculator.calculatePlayerToMonster(p, tank, 50).isMissed()) normalMiss++;
        }
        assertTrue(normalMiss > 0, "前置：普通入口对这种怪应当会 MISS（否则本测试没在测必中）");

        for (int i = 0; i < 200; i++) {
            DamageResult r = calculator.calculatePlayerToMonsterAlwaysHit(p, tank, 50);
            assertFalse(r.isMissed(), "必中入口不得 MISS（原版 dmUseAccuracy = 0）");
            assertTrue(r.getFinalDamage() >= 1, "必中且至少 1 点伤害");
        }
    }

    /**
     * **命中加成**（`SkillMods.accuracyBonusPct`）：原版 Jumping Crash 的"施法前临时加命中"
     * （`SkillSub.cpp:1935-1943`：`Attack_Rating += Attack_Rating * 表值 / 100`，发包后还原）。
     *
     * 做法：拿一只"刚好在命中率低档"的怪，加成后命中率应当**上升**（用同一只怪、同一角色，
     * 统计 400 次的命中次数 —— 加成把 Attack_Rating 放大 ⇒ 命中率单调不减）。
     */
    @Test
    void 命中加成提高命中率() {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setJob(4);
        p.setLevel(40);
        p.setStrength(60);
        p.setSpirit(20);
        p.setTalent(40);
        p.setAgility(40);
        p.setHealth(40);
        MonsterStats mob = new MonsterStats(40, 40, 0, 10000, 0, 1, 1);

        int baseHits = 0;
        int boostedHits = 0;
        for (int i = 0; i < 400; i++) {
            if (!calculator.calculatePlayerToMonster(p, mob, 50).isMissed()) baseHits++;
            if (!calculator.calculatePlayerToMonster(p, mob, 50,
                    new DamageCalculator.SkillMods(65, 0)).isMissed()) boostedHits++;
        }
        assertTrue(boostedHits > baseHits,
            "65% 命中加成后命中次数应严格多于无加成（base=" + baseHits + " boosted=" + boostedHits + "）");
    }
}
