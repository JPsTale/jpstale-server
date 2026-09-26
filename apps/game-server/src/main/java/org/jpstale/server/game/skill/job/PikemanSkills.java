package org.jpstale.server.game.skill.job;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.skill.CastContext;
import org.jpstale.server.game.skill.HitTarget;
import org.jpstale.server.game.skill.JobSkills;
import org.jpstale.server.game.skill.combat.SkillCombat;
import org.jpstale.server.game.skill.combat.TargetSelectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 枪手（Pikeman，job 4）的技能效果 —— 对照册：{@code docs/技能系统-pikeman.md}。
 *
 * <p>已迁：一转 3 个（Pike Wind / Critical Hit / Jumping Crash）；
 * 其余 17 个未迁 ⇒ {@link #handles} 为 false，起手走旧路（当普攻即时结算）。
 * 新迁一招：注册表加一行 + 写一个方法（出处写在方法注释上），别处零改动。
 */
@Slf4j
@Service
public class PikemanSkills implements JobSkills {

    /** 技能 id → 结算方法。**注册即迁移**：`handles`/`settle` 都看它，不另维护名单。 */
    private final Map<Integer, Function<CastContext, List<HitTarget>>> skills;

    @Autowired
    private SkillDataRegistry skillData;

    @Autowired
    private DamageCalculator damageCalculator;

    @Autowired
    private TargetSelectors targets;

    @Autowired
    private SkillCombat combat;

    public PikemanSkills() {
        Map<Integer, Function<CastContext, List<HitTarget>>> m = new LinkedHashMap<>();
        m.put(SkillIds.PIKE_WIND.id(), this::pikeWind);
        m.put(SkillIds.CRITICAL_HIT.id(), this::criticalHit);
        m.put(SkillIds.JUMPING_CRASH.id(), this::jumpingCrash);
        this.skills = Map.copyOf(m);
    }

    @Override
    public int job() {
        return 4;   // pikeman（skillId 高 16 位同此）
    }

    @Override
    public boolean handles(int skillId) {
        return skills.containsKey(skillId);
    }

    @Override
    public List<HitTarget> settle(CastContext c) {
        Function<CastContext, List<HitTarget>> fn = skills.get(c.skillId());
        return fn == null ? null : fn.apply(c);
    }

    /* ────────────── Pike Wind：以己为中心 AoE + 必中 + 推离 ────────────── */

    private List<HitTarget> pikeWind(CastContext c) {
        // Pike_Wind_Damage 是 **[10][2]**（min/max），必须走 table2d —— table1d 对二维表会直接抛
        double[][] dmg2 = c.table2d("Pike_Wind_Damage");
        double[] radiusTable = c.table1d("Pike_Wind_Push_Lenght");
        if (dmg2 == null || radiusTable == null || c.idx() >= dmg2.length || c.idx() >= radiusTable.length) {
            log.error("[Skill] Pike Wind 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        float radius = (float) radiusTable[c.idx()];

        // 选敌：以己为中心的圆，同图、存活、非召唤物；**必中**（dm_SelectRange(…, FALSE)）—— 在 TargetSelectors
        List<Monster> circle = targets.circleAround(c.self(), radius);

        // 伤害 = **面板攻击力掷点 × (1 + 表值%)** —— ⚠ **我方决定，与源码不同**（用户 2026-09-24 裁定）：
        //   源码此招是 `lpTransSkillAttackData->Power = GetRandomPos(Pike_Wind_Damage[Point][0..1])`
        //   （`Svr_Damge.cpp:4393`）—— 用**自己的表覆盖**攻击力，1 级只有 3..20 ⇒ 比普攻还低
        //   （用户实测："普攻 40 点，技能只打 20 点"）。同族技能 Ground Pike/Roar/Mechanic Bomb/Spark
        //   也都是"自己的表覆盖"；**大多数技能**则是 `pow = GetRandomPos(包的 Power[0], Power[1])` +
        //   技能自己的百分比（包的 `Power[0..1]` = 玩家面板 `Attack_Damage`，`Damage.cpp:809-810`）。
        //   EU 库（`skilldbnew.skilldata`）给 Pike Wind 也是自己的表（15-25），即"太弱"是共识。
        //   我们按用户口径把表读作**百分比区间**（1 级 3..20%、10 级 21..80%），伤害随面板攻击力走。
        int[] ap = c.attackPower();
        int atk = c.roll(ap[0], ap[1]);
        int pct = c.roll(dmg2[c.idx()][0], dmg2[c.idx()][1]);
        int power = atk + atk * pct / 100;
        List<HitTarget> hits = new ArrayList<>(circle.size());
        for (Monster m : circle) {
            // **必中**（原版 `dm_SelectRange(x,y,z,range,FALSE)` ⇒ `dmUseAccuracy = 0`，`Damage.cpp:428/454`）
            // —— Pike Wind 不做命中判定；用户 2026-09-24 实测"MISS 了，跟原版不一样"⇒ 已改必中入口。
            DamageResult r = damageCalculator.calculatePlayerToMonsterAlwaysHit(c.player(), m.combatStats(), power);
            combat.applyDamage(c.player(), c.self(), m, r, radius);
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), !r.isMissed()));
        }
        return hits;
    }

    /* ────────────── Critical Hit：单体 1 段/次 + 暴击率加成（两段 = 两次事件帧） ────────────── */

    private List<HitTarget> criticalHit(CastContext c) {
        double[] critTable = c.table1d("Critical_Hit_Critical");
        if (critTable == null || c.idx() >= critTable.length) {
            log.error("[Skill] Critical Hit 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        int critBonus = (int) critTable[c.idx()];
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), 0, critBonus);
        combat.applyDamage(c.player(), c.self(), m, r, 0);
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Jumping Crash：单体 1 段 + Power 百分比 + 恶魔加成 ────────────── */

    private List<HitTarget> jumpingCrash(CastContext c) {
        double[] dmgTable = c.table1d("Jumping_Crash_Damage");
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Jumping Crash 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        // 伤害 = **攻击力掷点** ×(1 + 表值%)（原版 `Power += Power*Jumping_Crash_Damage[Point]/100`；
        // 包里的 `Power` 是玩家攻击力，不是武器原始伤害）。
        // ⚠ 2026-09-24 实测修：此前用 `baseAttack`（**武器原始伤害** 3-5 那种）当基数 ⇒
        //   55% 加成后仍只有个位数伤害（用户报"固定 9 点"）。面板同源的攻击力区间是 `attackPower`。
        int[] ap = c.attackPower();
        int power = c.roll(ap[0], ap[1]);
        int boosted = power + power * (int) dmgTable[c.idx()] / 100;
        // 恶魔系 +30%（Svr_Damge.cpp:2834 逐字；⚠ 30 不是 desc 写的 100）
        if (m.getBrood() == Monster.Brood.DEMON) {
            boosted += boosted * 30 / 100;
        }
        // **施法前临时加命中**（原版把 Attack_Rating 按百分比放大、发包后还原，`SkillSub.cpp:1935-1943`）：
        // 我们服务端权威 ⇒ 判定时放大同样比例（不改玩家状态，"还原"天然成立）。
        int accBonus = accuracyBonusOf(c);
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), boosted,
                new DamageCalculator.SkillMods(accBonus, 0));
        combat.applyDamage(c.player(), c.self(), m, r, 0);
        log.info("[Skill] {} Jumping Crash p{} power={} boosted={} 打 {}#{}（brood={}）",
                c.player().getName(), c.idx() + 1, power, boosted, m.getName(), c.targetId(), m.getBrood());
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /** Jumping Crash 的"施法前临时加命中"表值（`Jumping_Crash_Attack_Rating[10] = {10,20,…,65}`，`sinSkill_Info.cpp:193`）。 */
    private int accuracyBonusOf(CastContext c) {
        double[] t = c.table1d("Jumping_Crash_Attack_Rating");
        if (t == null || c.idx() >= t.length) {
            log.error("[Skill] 命中加成表缺失：Jumping_Crash_Attack_Rating（idx={}）", c.idx());
            return 0;
        }
        return (int) t[c.idx()];
    }

    /* ────────────── 面板：伤害百分比（与结算同一张表） ────────────── */

    /**
     * **面板用**：该技能该等级的"伤害加成百分比"区间（`{min,max}`；单一值时两者相等）。
     *
     * `null` = 该技能**不是**"攻击力 ×(1+%)"模型（面板不显示伤害行，也不编一个数）。
     * 这里是本职业技能伤害模型的**唯一定义处**（与上面的 settle 方法用同一张表），面板只是读它。
     *
     * @param point 1 基技能等级
     */
    @Override
    public int[] powerPct(int skillId, int point) {
        int idx = point - 1;
        if (idx < 0) {
            return null;
        }
        if (skillId == SkillIds.PIKE_WIND.id()) {
            double[][] t = skillData.table2d("Pike_Wind_Damage");
            if (t == null || idx >= t.length) {
                return null;
            }
            return new int[]{(int) t[idx][0], (int) t[idx][1]};
        }
        if (skillId == SkillIds.JUMPING_CRASH.id()) {
            double[] t = skillData.table1d("Jumping_Crash_Damage");
            if (t == null || idx >= t.length) {
                return null;
            }
            return new int[]{(int) t[idx], (int) t[idx]};
        }
        // Critical Hit / 其余：伤害本身不加百分比（它的模型是"暴击率 +表值"）⇒ 不报
        return null;
    }
}
