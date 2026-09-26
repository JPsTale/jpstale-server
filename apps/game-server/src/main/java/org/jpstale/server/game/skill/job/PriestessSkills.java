package org.jpstale.server.game.skill.job;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.skill.CastContext;
import org.jpstale.server.game.skill.HitTarget;
import org.jpstale.server.game.skill.JobSkills;
import org.jpstale.server.game.skill.combat.SkillCombat;
import org.jpstale.server.game.skill.combat.TargetSelectors;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.proto.base.S2C_Recovery;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 祭司（Priestess，job 8）的技能效果 —— 对照册：{@code docs/技能系统规格书-08-priestess.md}。
 *
 * <p>已迁 6 招（范围裁定：T1 全部 —— 当前 rank 0 唯一可达的档；外加规格书已有完整小节的
 * Divine Lightning（J2）/ Chain Lightning（J4））：
 * <ul>
 *   <li><b>Healing</b>（T1.1）自我治疗：{@code rand(Healing_Heal[p][0] + Power2[0]/3 + Spirit/8,
 *       Healing_Heal[p][1] + Power2[1]/3 + Spirit/6)}（`Svr_Damge.cpp:3275-3293` 逐字；
 *       `Power2` = 面板攻击力 − 装备裸伤，`Damage.cpp:253-254`；`Critical[1]` = 主属性
 *       = Spirit（魔法职业），`Damage.cpp:266`）。</li>
 *   <li><b>Holy Bolt</b>（T1.2）单体：攻击力掷点 ×(1+{@code HolyBolt_Damage[p]}%)，**不暴击**
 *       （`Svr_Damge.cpp:3132-3136`）。</li>
 *   <li><b>Multi Spark</b>（T1.3）单体：火花数 {@code Param = rand(M_Spark_Num[p]/2+1, M_Spark_Num[p])}
 *       （客户端激活时随机，`SkillSub.cpp:2785-2792`），攻击力掷点 ×(1+{@code M_Spark_Damage[p]×Param}%)，
 *       对怪 +30%（`Svr_Damge.cpp:3139-3150`；源码 `if (lpChar)` = 目标是怪 —— 我们只有 PvM ⇒ 恒成立），
 *       **不暴击**。</li>
 *   <li><b>Holy Mind</b>（T1.4）对怪减益 15 秒：出手伤害 −{@code HolyMind_DecDamage[p]}%
 *       （`SkillSub.cpp:2817-2836` 发起、`OnSever.cpp:16600-16625` 落地、`character.cpp:14917-14918` 生效）。
 *       ⚠ <b>缺口（显式登记）</b>：源码按时长 ×(100−生物抗性)/100 缩短（`OnSever.cpp:16610-16613`），
 *       我们 {@code MonsterStats} 还没有抗性字段 ⇒ **固定 15 秒**（抗性系统与 DamageCalculator 的
 *       元素抗性 TODO 同一批补）。</li>
 *   <li><b>Divine Lightning</b>（T2.2）轮转扫描最多 {@code Divine_Lightning_Num[p]} 个敌人
 *       （3D 距离 ≤180、|dy|&lt;65），伤害 = **装备裸伤掷点**、必中（规格书 §3 逐字；
 *       `character.cpp:16266-16280` + `Damage.cpp:538`）。</li>
 *   <li><b>Chain Lightning</b>（T4.3）最近邻链（不是随机！）：主目标起、每次跳向距上一个最近者
 *       （XZ ≤{@code Chain_Lightning_Range[p]}、|dy|&lt;70、排除已选），最多
 *       {@code Chain_Lightning_Num[p]} 个；伤害 = 装备裸伤掷点、必中（规格书 §2 逐字）。</li>
 * </ul>
 *
 * <p><b>未迁（显式，走旧路）</b>：Meditation（被动，需回蓝挂钩）、Holy Reflection（受击侧反弹规则）、
 * Grand Healing（全队治疗 —— 原版语义未取全：跳过施法者本人的发送循环、无队伍时行为未确认，
 * 待补证后接入组队 API）、Vigor Ball / Resurrection / Extinction / Virtual Life / Glacial Spike /
 * Regeneration Field（维持型，U-08-8/9 未决）/ Summon Muspell（召唤系统）、5 转 4 个（无源码，
 * 服务端本就永久拒绝）。
 */
@Slf4j
@Service
public class PriestessSkills implements JobSkills {

    /** Divine Lightning 的轮转扫描位置（每玩家；规格书 §3.3：上次结束处继续扫，`netplay.cpp:12375` 初值 0）。 */
    private final Map<Long, Integer> divineFindCount = new java.util.concurrent.ConcurrentHashMap<>();

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

    @Autowired
    private PlayerService playerService;

    /** 治疗飘字广播（与普攻伤害广播同一条 AOI 通道） */
    @Autowired
    @Lazy
    private GameMessageSender messageSender;

    public PriestessSkills() {
        Map<Integer, Function<CastContext, List<HitTarget>>> m = new LinkedHashMap<>();
        m.put(SkillIds.HEALING.id(), this::healing);
        m.put(SkillIds.HOLY_BOLT.id(), this::holyBolt);
        m.put(SkillIds.MULTISPARK.id(), this::multiSpark);
        m.put(SkillIds.HOLY_MIND.id(), this::holyMind);
        m.put(SkillIds.DIVINE_LIGHTNING.id(), this::divineLightning);
        m.put(SkillIds.CHAIN_LIGHTNING.id(), this::chainLightning);
        this.skills = Map.copyOf(m);
    }

    @Override
    public int job() {
        return 8;   // priestess（skillId 高 16 位同此）
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

    /* ────────────── Healing（T1.1）：自我治疗 ────────────── */

    private List<HitTarget> healing(CastContext c) {
        double[][] heal2 = c.table2d("Healing_Heal");
        if (heal2 == null || c.idx() >= heal2.length) {
            log.error("[Skill] Healing 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Player p = c.player();
        // Power2 = 面板攻击力 − 装备裸伤（`Damage.cpp:253-254`：Power2 = Power − sItemInfo.Damage）；
        // Critical[1] = 施法者主属性，魔法职业 = Spirit（`Damage.cpp:266`）。
        int[] ap = c.attackPower();
        int[] wd = c.weaponDamage();
        int spirit = p.getSpirit();
        int min = (int) heal2[c.idx()][0] + (ap[0] - wd[0]) / 3 + spirit / 8;
        int max = (int) heal2[c.idx()][1] + (ap[1] - wd[1]) / 3 + spirit / 6;
        int amount = c.roll(min, max);

        int healed = Math.min(p.getMaxHp() - p.getHp(), amount);
        if (healed > 0) {
            p.setHp(p.getHp() + healed);
            playerService.persistStats(p);
        }
        PlayerSession session = playerService.sessionOf(p);
        PlayerEntity self = session != null ? session.getEntity() : null;
        // 回复广播：**满血也发**（源码 `rsPlayHealing` 对满血目标同样回包，量被 clamp 到 0；
        // 我们 current_hp 用 clamp 后的权威值）。0 量不发（0 = "没回复"是协议语义）。
        if (self != null && healed > 0) {
            playerService.sendPlayerStatus(session, p);
            messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), 50,
                    ServerMessage.newBuilder()
                            .setRecovery(S2C_Recovery.newBuilder()
                                    .setTargetId(p.getId())
                                    .setHpAmount(healed)
                                    .setCurrentHp(p.getHp())
                                    .build())
                            .build());
        }
        log.info("[Skill] {} Healing p{} 回复 {}（表 {}..{} + Power2 {}/3 + Spirit {}/8..6）",
                p.getName(), c.point(), healed, (int) heal2[c.idx()][0], (int) heal2[c.idx()][1], ap[0] - wd[0], spirit);
        return List.of();   // 治疗不是伤害：零目标（源码该 case 不走伤害结算）
    }

    /* ────────────── Holy Bolt（T1.2）：单体神圣弹，不暴击 ────────────── */

    private List<HitTarget> holyBolt(CastContext c) {
        double[] dmgTable = c.table1d("HolyBolt_Damage");
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Holy Bolt 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        int[] ap = c.attackPower();
        int power = c.roll(ap[0], ap[1]);
        power += power * (int) dmgTable[c.idx()] / 100;
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), power,
                DamageCalculator.SkillMods.withoutCrit());
        combat.applyDamage(c.player(), c.self(), m, r, 0);
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Multi Spark（T1.3）：单体多发火花，对怪 +30%，不暴击 ────────────── */

    private List<HitTarget> multiSpark(CastContext c) {
        double[] dmgTable = c.table1d("M_Spark_Damage");
        double[] numTable = c.table1d("M_Spark_Num");
        if (dmgTable == null || numTable == null || c.idx() >= dmgTable.length || c.idx() >= numTable.length) {
            log.error("[Skill] Multi Spark 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        // 火花数：激活时随机定死（`SkillSub.cpp:2785-2786`：cnt = rand(Num/2+1, Num)，随 SkillCode 上行）
        // —— 我们服务端权威，在这里掷同一区间。
        int num = (int) numTable[c.idx()];
        int sparks = c.roll(num / 2 + 1, num);
        int[] ap = c.attackPower();
        int power = c.roll(ap[0], ap[1]);
        power += power * (int) dmgTable[c.idx()] * sparks / 100;
        // +30% on Monsters（`Svr_Damge.cpp:3146-3148` 的 `if (lpChar)`；我们只有 PvM ⇒ 目标恒是怪）
        power += power * 30 / 100;
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), power,
                DamageCalculator.SkillMods.withoutCrit());
        combat.applyDamage(c.player(), c.self(), m, r, 0);
        log.info("[Skill] {} Multi Spark p{} 火花 {} power={} 打 {}#{}",
                c.player().getName(), c.point(), sparks, power, m.getName(), c.targetId());
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Holy Mind（T1.4）：对怪减益 15 秒 ────────────── */

    private List<HitTarget> holyMind(CastContext c) {
        double[] decTable = c.table1d("HolyMind_DecDamage");
        if (decTable == null || c.idx() >= decTable.length) {
            log.error("[Skill] Holy Mind 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        // 源码必须带目标（`SkillSub.cpp:2824` BeginSkill(…, lpChar, …)）
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        int decPct = (int) decTable[c.idx()];
        m.applyHolyMind(decPct, 15_000L);   // 15 秒 = `LParam 15`（`SkillSub.cpp:2832`）
        log.info("[Skill] {} Holy Mind p{} 怪 {}#{} 出手伤害 -{}% 15 秒",
                c.player().getName(), c.point(), m.getName(), m.getId(), decPct);
        return List.of();   // 减益不是伤害：零目标
    }

    /* ────────────── Divine Lightning（T2.2）：轮转扫描 + 裸伤 + 必中 ────────────── */

    private List<HitTarget> divineLightning(CastContext c) {
        double[] numTable = c.table1d("Divine_Lightning_Num");
        if (numTable == null || c.idx() >= numTable.length) {
            log.error("[Skill] Divine Lightning 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        int maxTargets = (int) numTable[c.idx()];
        // 轮转：从"上次结束处"继续扫（每玩家滚动位）。源码扫的是固定的玩家数组下标；
        // 我们按 **monster id 升序**的稳定序列滚动 —— 保证"连续两次施放选中集合不同"这个可观测行为。
        List<Monster> candidates = new ArrayList<>(targets.monstersOnMap(c.self()));
        candidates.sort(java.util.Comparator.comparingLong(Monster::getId));
        int start = divineFindCount.getOrDefault(c.player().getId(), 0);
        List<Monster> picked = scanRoundRobin(candidates, start, maxTargets,
                c.self(), 180.0, 65);
        divineFindCount.put(c.player().getId(), (start + picked.size()) % Math.max(1, candidates.size()));

        return settleWeaponDamageBurst(c, picked);
    }

    /* ────────────── Chain Lightning（T4.3）：最近邻链 + 裸伤 + 必中 ────────────── */

    private List<HitTarget> chainLightning(CastContext c) {
        double[] numTable = c.table1d("Chain_Lightning_Num");
        double[] rangeTable = c.table1d("Chain_Lightning_Range");
        if (numTable == null || rangeTable == null || c.idx() >= numTable.length || c.idx() >= rangeTable.length) {
            log.error("[Skill] Chain Lightning 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        // 链的第 0 个 = 主目标（`character.cpp:17066`：`dm_SelectDamageChainCount(this, chrAttackTarget, …)`；
        // 没有主目标整招不成立 —— 源码 `if (chrAttackTarget && point)` 直接不触发）
        Monster first = targets.single(c.player(), c.self(), c.targetId());
        if (first == null) {
            log.info("[Skill] {} Chain Lightning 无主目标 ⇒ 不触发（源码同此）", c.player().getName());
            return List.of();
        }
        List<Monster> picked = chainNearest(targets.monstersOnMap(c.self()), first,
                (int) numTable[c.idx()], (float) rangeTable[c.idx()]);
        return settleWeaponDamageBurst(c, picked);
    }

    /** Divine / Chain Lightning 共用：**一次**裸伤掷点 → 逐目标必中结算（同一 power，各自的防御/吸收在公式内生效）。 */
    private List<HitTarget> settleWeaponDamageBurst(CastContext c, List<Monster> picked) {
        int[] wd = c.weaponDamage();
        int power = c.roll(wd[0], wd[1]);
        List<HitTarget> hits = new ArrayList<>(picked.size());
        for (Monster m : picked) {
            DamageResult r = damageCalculator.calculatePlayerToMonsterAlwaysHit(c.player(), m.combatStats(), power);
            combat.applyDamage(c.player(), c.self(), m, r, 0);
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
        }
        return hits;
    }

    /* ────────────── 选敌算法（纯函数，可直测；规格书 §2.3 / §3.3） ────────────── */

    /**
     * **轮转扫描**（Divine Lightning，规格书 §3.3 / `Damage.cpp:538`）：
     * 从 {@code startOffset} 起按序列滚动，选 3D 距离 ≤{@code range}、|dy|&lt;{@code dyLimit} 的前
     * {@code maxTargets} 个。不是随机 —— 是滚动扫描（连续两次施放选中集合不同 = 可观测判据 R1）。
     */
    static List<Monster> scanRoundRobin(List<Monster> candidates, int startOffset, int maxTargets,
                                        PlayerEntity self, double range, int dyLimit) {
        List<Monster> picked = new ArrayList<>(maxTargets);
        int n = candidates.size();
        for (int i = 0; i < n && picked.size() < maxTargets; i++) {
            Monster m = candidates.get((startOffset + i) % n);
            double dx = m.getX() - self.getX();
            double dy = m.getY() - self.getY();
            double dz = m.getZ() - self.getZ();
            if (dx * dx + dy * dy + dz * dz <= range * range && Math.abs(dy) < dyLimit) {
                picked.add(m);
            }
        }
        return picked;
    }

    /**
     * **最近邻链**（Chain Lightning，规格书 §2.3 / `Damage.cpp:662`）：
     * 第 0 个 = 主目标；之后每轮选"距上一个已选**最近**"的（XZ 平面，|dy|&lt;70），
     * 排除已选，直到 {@code maxTargets} 个。链指针前移（`lpLinkChar = lpMinChar`）。
     */
    static List<Monster> chainNearest(List<Monster> candidates, Monster first, int maxTargets, float jumpRange) {
        List<Monster> picked = new ArrayList<>(maxTargets);
        picked.add(first);
        double rangeSq = (double) jumpRange * jumpRange;
        Monster link = first;
        while (picked.size() < maxTargets) {
            Monster best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (Monster m : candidates) {
                if (picked.contains(m)) {
                    continue;   // 排除已选过的（步④）
                }
                double dx = m.getX() - link.getX();
                double dz = m.getZ() - link.getZ();
                double distSq = dx * dx + dz * dz;   // 只算 XZ（步⑤）
                if (distSq < bestDistSq && Math.abs(m.getY() - link.getY()) < 70) {   // 高度差单独限制（步⑥）
                    bestDistSq = distSq;
                    best = m;
                }
            }
            if (best == null || bestDistSq > rangeSq) {
                break;   // 候选耗尽或最近的也超出跳跃范围
            }
            picked.add(best);
            link = best;   // 链指针前移（步⑦）
        }
        return picked;
    }

    /* ────────────── 面板：伤害百分比（与结算同一张表） ────────────── */

    /**
     * 面板口径：Holy Bolt / Multi Spark 是"攻击力 ×(1+表值%)"模型 ⇒ 报表值
     * （Multi Spark 的实际倍率随火花数上浮，面板显示**基数** `M_Spark_Damage[p]`，火花数是运行态）。
     * 其余（Healing 是回复、Holy Mind 是减益、Divine/Chain Lightning 是裸伤替换）⇒ null 不报。
     */
    @Override
    public int[] powerPct(int skillId, int point) {
        int idx = point - 1;
        if (idx < 0) {
            return null;
        }
        if (skillId == SkillIds.HOLY_BOLT.id()) {
            double[] t = skillData.table1d("HolyBolt_Damage");
            if (t == null || idx >= t.length) {
                return null;
            }
            return new int[]{(int) t[idx], (int) t[idx]};
        }
        if (skillId == SkillIds.MULTISPARK.id()) {
            double[] t = skillData.table1d("M_Spark_Damage");
            if (t == null || idx >= t.length) {
                return null;
            }
            return new int[]{(int) t[idx], (int) t[idx]};
        }
        return null;
    }
}
