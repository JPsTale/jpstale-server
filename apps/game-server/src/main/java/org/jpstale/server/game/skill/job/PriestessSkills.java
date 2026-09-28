package org.jpstale.server.game.skill.job;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.skill.CastContext;
import org.jpstale.server.game.skill.HitTarget;
import org.jpstale.server.game.skill.JobSkills;
import org.jpstale.server.game.skill.SkillBuffStates;
import org.jpstale.server.game.skill.combat.SkillCombat;
import org.jpstale.server.game.skill.combat.TargetSelectors;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.proto.base.S2C_Recovery;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 祭司（Priestess，job 8）的技能效果 —— 对照册：{@code docs/技能系统规格书-08-priestess.md}。
 *
 * <p>已迁 <b>16/20（T1–T4 全部）</b>；5 转 4 招无源码（服务端本就永久拒绝），不在本类。
 * 范围依据：rank 0 开 1..4、rank 1 开 5..8，转职/GM 提档后后续转必须可用（用户 2026-09-26 指示）。
 * 2026-09-28 用户指示"祭司做到 4 转第 4 个"⇒ T3/T4 剩余 7 招全部迁入：
 * <ul>
 *   <li><b>Healing</b>（T1.1）治疗：**有玩家目标治目标，没有治自己**（用户 2026-09-26 指正；
 *       原版按上报序号治疗，`rsPlayHealing` 对 char/user 都生效，`OnSever.cpp:16478`）。
 *       回复量 = {@code rand(Healing_Heal[p][0] + Power2[0]/3 + Spirit/8, [1] + Power2[1]/3 + Spirit/6)}
 *       （`Svr_Damge.cpp:3275-3293`；`Power2` = 面板攻击力 − 装备裸伤，`Damage.cpp:253-254`；
 *       `Critical[1]` = 主属性 = Spirit（魔法职业），`Damage.cpp:266`）。</li>
 *   <li><b>Holy Bolt</b>（T1.2）单体：攻击力掷点 ×(1+{@code HolyBolt_Damage[p]}%)，**不暴击**
 *       （`Svr_Damge.cpp:3132-3136`）。</li>
 *   <li><b>Multi Spark</b>（T1.3）单体 **N 道光芒、每道 = 1×攻击力**（独立命中/暴击/防御/吸收）：
 *       道数 {@code N = rand(M_Spark_Num[p]/2+1, M_Spark_Num[p])}。
 *       ⚠ **2026-09-26 用户实测裁定，取代源码公式**（源码的
 *       {@code Power ×(1+M_Spark_Damage×Param/100)+30%}，`Svr_Damge.cpp:3139-3150` 作废）——
 *       "这是祭司的主力单体输出技能"。</li>
 *   <li><b>Holy Mind</b>（T1.4）对怪减益 15 秒：出手伤害 −{@code HolyMind_DecDamage[p]}%
 *       （`SkillSub.cpp:2817-2836`、`OnSever.cpp:16600-16625`、`character.cpp:14917-14918`）。
 *       ⚠ <b>缺口（显式登记）</b>：生物抗性缩短时长没做（`MonsterStats` 无抗性字段）。</li>
 *   <li><b>Meditation</b>（T2.1，被动）回蓝累加 {@code Meditation_Regen[p]}/秒 —— 在
 *       `PlayerStatCalculator.applySkillPassives`（`sinInvenTory1.cpp:7830-7832`），不进本类。</li>
 *   <li><b>Divine Lightning</b>（T2.2）轮转扫描最多 {@code Divine_Lightning_Num[p]} 个敌人
 *       （3D ≤180、|dy|&lt;65），伤害 = **装备裸伤掷点**、必中（规格书 §3）。</li>
 *   <li><b>Holy Reflection</b>（T2.3）限时自增益 {@code Holy_Reflection_Time[p]} 秒：期间**亡灵**怪
 *       攻击祭司 ⇒ 攻击者吃 "其伤害掷点 ×{@code Holy_Reflection_Return_Damage[p]}% −吸收" 的反弹
 *       （`OnSever.cpp:34126-34131` + `rsProcessAttack_SkillHolyReflection :34671-34730`）。
 *       生效窗口在 `SkillBuffStates`；反弹落地在 `AiEngine.reflectHolyReflection`。
 *       ⚠ 同样缺生物抗性缩放（源码 `:34698-34705`）。</li>
 *   <li><b>Grand Healing</b>（T2.4）**只治队友**（原版发送循环明确跳过施法者本人；
 *       无队伍 = 无效果，`rsPlayGrandHealing` `OnSever.cpp:16532-16570`；规格书总表"治疗队友"）。
 *       一次掷点全队同量：{@code rand(Grand_Healing[p][0] + Spirit/8 + Power2[0]/3, …/6 …/3)}。
 *       不按距离过滤（源码无此判断；Virtual Life 的减量未做——那技能还没实现）。</li>
 *   <li><b>Chain Lightning</b>（T4.3）最近邻链（不是随机！），裸伤掷点、必中（规格书 §2）。</li>
 *   <li><b>Vigor Ball</b>（T3.1）单体：面板掷 ×(1+{@code Vigor_Ball_Damage[p]}%)（26..51%）、
 *       禁暴击；先过"**必须持械**"门 —— {@code Power>Power2}（两端，`Svr_Damge.cpp:3498`，
 *       Power2 = 面板−装备裸伤 ⇒ 差&gt;0 即有武器），徒手 {@code return FALSE}（整招无伤，不是退普攻）。</li>
 *   <li><b>Resurrection</b>（T3.2）复活**死亡玩家**：{@code rand%100 < Resurrection_Percent[p]}
 *       （40..94%，`OnSever.cpp:34259`）⇒ 原地半血救起（{@code sinSetLife(Life[1]/2)}，
 *       `netplay.cpp:6547-6566`；无经验/金币代价 —— 原版 {@code PlayUsed_Resurrection} 跳过惩罚）。
 *       原版目标是**死亡队友**（客户端 {@code FindDeadPartyUser}）；⚠ 我方客户端暂不能选死亡玩家
 *       （缺口登记），targetId 解析不到死亡玩家 ⇒ 显式不救。</li>
 *   <li><b>Extinction</b>（T3.3）**只打亡灵**的范围咒杀（`dm_SelectRange(…,160,FALSE)` 圆 160 必中，
 *       `SkillSub.cpp:633-634`）：对每个范围**内且 Brood==UNDEAD** 的怪，{@code rand%100 <
 *       Extinction_Percent[p]+等级/5}（抗性缩减未做，无抗性列）命中 ⇒ 扣**当前生命**的
 *       {@code Extinction_Amount[p]}%（20..52%，`Svr_Damge.cpp:2622-2700`），未命中无飘字。</li>
 *   <li><b>Virtual Life</b>（T3.4）限时减伤 buff：**按代码是减伤不是"提升生命上限"**
 *       （`character.cpp:15112`：{@code Power -= Power*Virtual_Life_Percent[p]/100}，2..13%，
 *       90..270 秒）—— 应用在 AiEngine 受击侧（源码同位置）。自施**无条件覆盖**、对队友
 *       **仅在过期后**才可再施（`OnSever.cpp:34287-34306` 两分支不对称，照抄）。</li>
 *   <li><b>Glacial Spike</b>（T4.1）身前矩形 AoE（横向 ±50、前方 0..340，必中，
 *       `character.cpp:17017-17023` 的 {@code dm_SelectRangeBox}）：面板掷 ×(1+
 *       {@code Glacial_Spike_Damage[p]}%（150..195%））+ 减速 {@code PlaySlowSpeed=200、time=8}
 *       （= 200/256 比例、**8 秒** —— time 单位是秒，客户端 `Param2×70` 帧 @70fps，
 *       `Svr_Damge.cpp:1769-1776` + `character.cpp:10740`；第一版推成 1.83s 是错的）。⚠ 原版对怪的减速
 *       是**客户端**表现（服务端只管玩家目标）；我们的怪是服务端权威移动 ⇒ 服务端持态保持可观测行为。</li>
 *   <li><b>Regeneration Field</b>（T4.2，"上吊"）限时再生场（35..80 秒）：自己 {@code Life_Regen +=
 *       LifeRegen[p]} 全额 + {@code Mana_Regen += ManaRegen[p]} 全额；**队友**（施法者 XZ ≤
 *       {@code Area[p]}（250..340）、高度差 &lt;16）生命全额、**魔法减半**（{@code Flag=1+Party}，
 *       `sinSkill.cpp:7504`）。数值应用在 RegenerationService 每秒 tick（`sinInvenTory.cpp:8971`）。</li>
 *   <li><b>Summon Muspell</b>（T4.4）持续 120..300 秒的**被动**守佑 —— 源码里**没有任何召唤物生成**
 *       （"Muspell"是 {@code SkillCelestialMusPel} 的环绕火球视觉，`character.cpp:14794`）：
 *       受击时 ① {@code rand%100 < BlockPercent[p]}（5..14%）⇒ 整刀闪避（`Svr_Damge.cpp:1294`）；
 *       ② 攻击者是亡灵 ⇒ 吸收伤害的 {@code UndeadAbsorbPercent[p]}%（10..46%）变成回血
 *       （`character.cpp:15328` 打包 → 客户端 `:9103-9115` 消费为回血）。应用在 AiEngine 受击侧。</li>
 * </ul>
 *
 * <p><b>未迁（显式，走旧路）</b>：仅 5 转 4 个（Divine Force / Ice Meteorite / Thunderstorm /
 * Divine Cleansing —— 无源码，服务端本就永久拒绝，用户 2026-09-28 指示"暂时不动"）。
 * <p><b>客户端缺口（服务端已就绪）</b>：技能施法的目标解析目前只认怪（`WorldView.beginSelfSkill`
 * 的 `monsterIdOfRoot`），Healing 治玩家目标 / Resurrection 选死亡玩家要等客户端把（死亡）玩家
 * 纳入技能瞄准 —— 服务端按 charId 的解析两侧都已就位。
 */
@Slf4j
@Service
public class PriestessSkills implements JobSkills {

    /** Divine Lightning 的轮转扫描位置（每玩家；规格书 §3.3：上次结束处继续扫，`netplay.cpp:12375` 初值 0）。 */
    private final Map<Long, Integer> divineFindCount = new java.util.concurrent.ConcurrentHashMap<>();

    /** 技能 id → 结算方法。**注册即迁移**：`handles`/`settle` 都看它，不另维护名单。
     *  ⚠ Meditation 是被动，走 `PlayerStatCalculator` 的属性层，**不注册**在这里。 */
    private final Map<Integer, Function<CastContext, List<HitTarget>>> skills;

    @Autowired
    private SkillDataRegistry skillData;

    @Autowired
    private DamageCalculator damageCalculator;

    @Autowired
    private TargetSelectors targets;

    @Autowired
    private SkillCombat combat;

    /** 死亡/复活状态机（Resurrection 的原地救起在那里，与三选项复活同一份状态） */
    @Autowired
    private org.jpstale.server.game.service.CombatService combatService;

    /** 左上角 buff 条的唯一生产者（技能 buff 施放后即时可见） */
    @Autowired
    private org.jpstale.server.game.service.BuffStateService buffStateService;

    @Autowired
    private PlayerService playerService;

    /** 治疗广播（与普攻伤害同一 AOI 通道；治疗怪时用） */
    @Autowired
    private GameMessageSender messageSender;

    /** Grand Healing 的全队名单 */
    @Autowired
    private org.jpstale.server.game.service.PartyService partyService;

    /** Holy Reflection 的生效窗口 */
    @Autowired
    private SkillBuffStates skillBuffStates;

    public PriestessSkills() {
        Map<Integer, Function<CastContext, List<HitTarget>>> m = new LinkedHashMap<>();
        m.put(SkillIds.HEALING.id(), this::healing);
        m.put(SkillIds.HOLY_BOLT.id(), this::holyBolt);
        m.put(SkillIds.MULTISPARK.id(), this::multiSpark);
        m.put(SkillIds.HOLY_MIND.id(), this::holyMind);
        m.put(SkillIds.HOLY_REFLECTION.id(), this::holyReflection);
        m.put(SkillIds.GRAND_HEALING.id(), this::grandHealing);
        m.put(SkillIds.DIVINE_LIGHTNING.id(), this::divineLightning);
        m.put(SkillIds.CHAIN_LIGHTNING.id(), this::chainLightning);
        m.put(SkillIds.VIGOR_BALL.id(), this::vigorBall);
        m.put(SkillIds.RESURRECTION.id(), this::resurrection);
        m.put(SkillIds.EXTINCTION.id(), this::extinction);
        m.put(SkillIds.VIRTUAL_LIFE.id(), this::virtualLife);
        m.put(SkillIds.GLACIAL_SPIKE.id(), this::glacialSpike);
        m.put(SkillIds.REGENERATION_FIELD.id(), this::regenerationField);
        m.put(SkillIds.SUMMON_MUSPELL.id(), this::summonMuspell);
        this.skills = Map.copyOf(m);
    }

    @Override
    public int job() {
        return 8;
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

    /* ────────────── Healing（T1.1）：治疗 —— 有目标治目标（角色：玩家或怪），没有治自己 ────────────── */

    private static final double HEAL_RANGE = 180;

    private List<HitTarget> healing(CastContext c) {
        double[][] heal2 = c.table2d("Healing_Heal");
        if (heal2 == null || c.idx() >= heal2.length) {
            log.error("[Skill] Healing 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Player p = c.player();
        int[] ap = c.attackPower();
        int[] wd = c.weaponDamage();
        int spirit = p.getSpirit();
        int min = (int) heal2[c.idx()][0] + (ap[0] - wd[0]) / 3 + spirit / 8;
        int max = (int) heal2[c.idx()][1] + (ap[1] - wd[1]) / 3 + spirit / 6;
        int amount = c.roll(min, max);

        Monster healMonster = null;
        {
            Monster m = targets.aliveMonster(c.targetId());
            if (m != null) {
                double dx = m.getX() - c.self().getX();
                double dz = m.getZ() - c.self().getZ();
                if (dx * dx + dz * dz <= HEAL_RANGE * HEAL_RANGE) {
                    healMonster = m;
                }
            }
        }
        if (healMonster != null) {
            int amount2 = Math.min(healMonster.getMaxHp() - healMonster.getHp(), amount);
            if (amount2 > 0) {
                healMonster.setHp(healMonster.getHp() + amount2);
                messageSender.broadcastToArea(c.self().getMapId(),
                        (float) healMonster.getX(), (float) healMonster.getZ(), AOIManager.VIEW_RANGE,
                        ServerMessage.newBuilder()
                                .setRecovery(S2C_Recovery.newBuilder()
                                        .setTargetId(healMonster.getId())
                                        .setHpAmount(amount2)
                                        .setCurrentHp(healMonster.getHp())
                                        .build())
                                .build());
            }
            log.info("[Skill] {} Healing p{} → 怪 {}#{} 回复 {}（原版 lpChar 分支）",
                    p.getName(), c.point(), healMonster.getName(), healMonster.getId(), amount2);
            return List.of();   // 治疗不是伤害：零目标
        }
        Player target = resolvePlayerTarget(c);
        if (target == null) {
            log.warn("[Skill] {} Healing 目标 id={} 解析不到（既不是怪也不是在场玩家）⇒ 本次不治疗"
                    + "（原版 rsPlayHealing 找不到 serial 时 return FALSE）", p.getName(), c.targetId());
            return List.of();
        }
        boolean self = (target == p);
        int healed = Math.min(target.getMaxHp() - target.getHp(), amount);
        if (healed > 0) {
            target.setHp(target.getHp() + healed);
            playerService.persistStats(target);
        }
        // 目标自己的 HUD（HP/MP/经验条）—— 只发给他本人（`sendPlayerStatus` 是逐会话的）
        PlayerSession ts = playerService.sessionOf(target);
        if (ts != null) {
            playerService.sendPlayerStatus(ts, target);
        }

        messageSender.broadcastToArea(c.self().getMapId(), (float) c.self().getX(), (float) c.self().getZ(),
                AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder()
                        .setRecovery(S2C_Recovery.newBuilder()
                                .setTargetId(target.getId())
                                .setHpAmount(amount)
                                .setCurrentHp(target.getHp())
                                .build())
                        .build());
        log.info("[Skill] {} Healing p{} → {}（{}）回复 {}（表 {}..{} + Power2 {}/3 + Spirit {}/8..6）",
                p.getName(), c.point(), target.getName(), self ? "自己" : "目标", healed,
                (int) heal2[c.idx()][0], (int) heal2[c.idx()][1], ap[0] - wd[0], spirit);
        return List.of();   // 治疗不是伤害：零目标（源码该 case 不走伤害结算）
    }

    private Player resolvePlayerTarget(CastContext c) {
        long targetId = c.targetId();
        Player self = c.player();
        if (targetId <= 0 || targetId == self.getId()) {
            return self;
        }
        Player candidate = playerService.byId(targetId);
        if (candidate == null) {
            return null;
        }
        PlayerEntity candEntity = playerService.entityOf(candidate);
        if (candEntity == null) {
            log.warn("[Skill] {} Healing 目标 {} 不在场上（无实体）⇒ 本次不治疗",
                    self.getName(), candidate.getName());
            return null;
        }
        if (candEntity.isDead()) {
            log.info("[Skill] {} Healing 目标 {} 已死亡 ⇒ 不加血（原版 `Life[0] > 0` 才加）",
                    self.getName(), candidate.getName());
            return candidate;
        }
        double dx = candEntity.getX() - c.self().getX();
        double dz = candEntity.getZ() - c.self().getZ();
        if (dx * dx + dz * dz > HEAL_RANGE * HEAL_RANGE) {
            log.warn("[Skill] {} Healing 目标 {} 超距（>{}，`GetSkillDistRange`）⇒ 本次不治疗"
                            + "（不改成治自己 —— 目标是客户端选定的那一个）",
                    self.getName(), candidate.getName(), HEAL_RANGE);
            return null;
        }
        return candidate;
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
        combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Multi Spark（T1.3）：N 道光芒，每道 = 一整次攻击（用户裁定） ────────────── */

    private List<HitTarget> multiSpark(CastContext c) {
        int sparks = c.sparkCount();
        if (sparks < 1) {
            log.warn("[Skill] {} Multi Spark 事件帧没有起手道数 ⇒ 不结算", c.player().getName());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        double[] dmgTable = c.table1d("M_Spark_Damage");
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Multi Spark 加成表 M_Spark_Damage 缺失（idx={}）⇒ 本次不放", c.idx());
            return List.of();
        }
        int[] ap = c.attackPower();
        int roll = c.roll(ap[0], ap[1]);
        int pct = (int) dmgTable[c.idx()];
        int perBolt = roll + roll * pct * sparks / 100;   // Param = N（道数）
        perBolt += perBolt * 30 / 100;                    // 对怪 +30%
        int power = perBolt * sparks;
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), power,
                DamageCalculator.SkillMods.withoutCrit());
        combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());   // **一次落地**：一次死亡检查/奖励；skillId 同上
        log.info("[Skill] {} Multi Spark p{} {} 道：面板掷 {} +{}%×{} 再 +30% ⇒ 每道 {}，合计 {} 打 {}#{}",
                c.player().getName(), c.point(), sparks, roll, pct, sparks, perBolt, power,
                m.getName(), m.getId());
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Holy Mind（T1.4）：对怪减益 15 秒 ────────────── */

    private List<HitTarget> holyMind(CastContext c) {
        double[] decTable = c.table1d("HolyMind_DecDamage");
        if (decTable == null || c.idx() >= decTable.length) {
            log.error("[Skill] Holy Mind 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
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


    private List<HitTarget> holyReflection(CastContext c) {
        double[] timeTable = c.table1d("Holy_Reflection_Time");
        double[] retTable = c.table1d("Holy_Reflection_Return_Damage");
        if (timeTable == null || retTable == null || c.idx() >= timeTable.length || c.idx() >= retTable.length) {
            log.error("[Skill] Holy Reflection 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        int durationSec = (int) timeTable[c.idx()];
        int returnPct = (int) retTable[c.idx()];
        skillBuffStates.apply(c.player().getId(), c.skillId(), durationSec * 1000L, returnPct);
        buffStateService.push(playerService.sessionOf(c.player()), c.player());   // buff 条即时出现
        log.info("[Skill] {} Holy Reflection p{} 圣盾 {} 秒，亡灵反弹 {}%",
                c.player().getName(), c.point(), durationSec, returnPct);
        return List.of();
    }


    private List<HitTarget> grandHealing(CastContext c) {
        double[][] heal2 = c.table2d("Grand_Healing");
        if (heal2 == null || c.idx() >= heal2.length) {
            log.error("[Skill] Grand Healing 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Player p = c.player();
        int[] ap = c.attackPower();
        int[] wd = c.weaponDamage();
        int spirit = p.getSpirit();
        int min = (int) heal2[c.idx()][0] + (ap[0] - wd[0]) / 3 + spirit / 8;
        int max = (int) heal2[c.idx()][1] + (ap[1] - wd[1]) / 3 + spirit / 6;
        int amount = c.roll(min, max);

        List<Player> members = partyService.membersOf(p.getId());
        int healed = 0;
        for (Player member : members) {
            if (member.isDead()) {
                continue;
            }
            int take = Math.min(member.getMaxHp() - member.getHp(), amount);
            if (take <= 0) {
                continue;
            }
            member.setHp(member.getHp() + take);
            healed++;
            playerService.persistStats(member);
            PlayerSession ts = playerService.sessionOf(member);
            if (ts != null) {
                playerService.sendPlayerStatus(ts, member);
            }
            messageSender.broadcastToArea(c.self().getMapId(), (float) c.self().getX(), (float) c.self().getZ(),
                    AOIManager.VIEW_RANGE,
                    ServerMessage.newBuilder()
                            .setRecovery(S2C_Recovery.newBuilder()
                                    .setTargetId(member.getId())
                                    .setHpAmount(amount)
                                    .setCurrentHp(member.getHp())
                                    .build())
                            .build());
        }
        log.info("[Skill] {} Grand Healing p{} 全队治疗 {} 点 → {} 名队友（队伍 {} 人）",
                p.getName(), c.point(), amount, healed, members.size());
        return List.of();
    }

    private List<HitTarget> divineLightning(CastContext c) {
        double[] numTable = c.table1d("Divine_Lightning_Num");
        double[] dmgTable = c.table1d("Divine_Lightning_Damage");
        if (numTable == null || c.idx() >= numTable.length) {
            log.error("[Skill] Divine Lightning 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Divine Lightning 加成表 Divine_Lightning_Damage 缺失（idx={}）⇒ 本次不放", c.idx());
            return List.of();
        }
        int maxTargets = (int) numTable[c.idx()];
        List<Monster> candidates = new ArrayList<>(targets.monstersOnMap(c.self()));
        candidates.sort(java.util.Comparator.comparingLong(Monster::getId));
        int start = divineFindCount.getOrDefault(c.player().getId(), 0);
        List<Monster> picked = scanRoundRobin(candidates, start, maxTargets,
                c.self(), 180.0, 65);
        divineFindCount.put(c.player().getId(), (start + picked.size()) % Math.max(1, candidates.size()));
        if (picked.isEmpty()) return List.of();

        int[] ap = c.attackPower();
        int roll = c.roll(ap[0], ap[1]);
        int pct = (int) dmgTable[c.idx()];
        int power = roll + roll * pct / 100;
        List<HitTarget> hits = new ArrayList<>(picked.size());
        for (Monster m : picked) {
            int per = power;
            if (m.getBrood() == Monster.Brood.UNDEAD) per += per / 2;
            DamageResult r = damageCalculator.calculatePlayerToMonsterAlwaysHit(c.player(), m.combatStats(), per);
            combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
        }
        log.info("[Skill] {} Divine Lightning p{}：面板掷 {} +{}% ⇒ {}，命中 {} 目标（不死系各再 +50%）",
                c.player().getName(), c.point(), roll, pct, power, hits.size());
        return hits;
    }

    /* ────────────── Chain Lightning（T4.3）：最近邻链 + 裸伤 + 必中 ────────────── */

    private List<HitTarget> chainLightning(CastContext c) {
        double[] numTable = c.table1d("Chain_Lightning_Num");
        double[] rangeTable = c.table1d("Chain_Lightning_Range");
        if (numTable == null || rangeTable == null || c.idx() >= numTable.length || c.idx() >= rangeTable.length) {
            log.error("[Skill] Chain Lightning 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Monster first = targets.single(c.player(), c.self(), c.targetId());
        if (first == null) {
            log.info("[Skill] {} Chain Lightning 无主目标 ⇒ 不触发（源码同此）", c.player().getName());
            return List.of();
        }
        List<Monster> picked = chainNearest(targets.monstersOnMap(c.self()), first,
                (int) numTable[c.idx()], (float) rangeTable[c.idx()]);
        if (picked.isEmpty()) return List.of();

        double[] dmgTable = c.table1d("Chain_Lightning_Damage");
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Chain Lightning 加成表 Chain_Lightning_Damage 缺失（idx={}）⇒ 本次不放", c.idx());
            return List.of();
        }
        int[] ap = c.attackPower();
        int power = c.roll(ap[0], ap[1]);
        power += power * (int) dmgTable[c.idx()] / 100;
        log.info("[Skill] {} Chain Lightning p{}：面板掷 {} ×{}% ⇒ {}，命中 {} 目标",
                c.player().getName(), c.point(), power * 100 / (100 + (int) dmgTable[c.idx()]),
                100 + (int) dmgTable[c.idx()], power, picked.size());
        return settleWeaponDamageBurst(c, picked, power);
    }

    private List<HitTarget> settleWeaponDamageBurst(CastContext c, List<Monster> picked, int power) {
        List<HitTarget> hits = new ArrayList<>(picked.size());
        for (Monster m : picked) {
            DamageResult r = damageCalculator.calculatePlayerToMonsterAlwaysHit(c.player(), m.combatStats(), power);
            combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
        }
        return hits;
    }

    /* ────────────── 选敌算法（纯函数，可直测；规格书 §2.3 / §3.3） ────────────── */

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

    /* ────────────── Vigor Ball（T3.1）：持械门 + 面板×(1+表值%)，不暴击 ────────────── */

    private List<HitTarget> vigorBall(CastContext c) {
        double[] dmgTable = c.table1d("Vigor_Ball_Damage");
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Vigor Ball 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        int[] wd = c.weaponDamage();
        if (wd[0] <= 0 || wd[1] <= 0) {
            log.info("[Skill] {} Vigor Ball p{} 徒手 ⇒ 不结算（源码 `Power>Power2` 持械门）",
                    c.player().getName(), c.point());
            return List.of();
        }
        int[] ap = c.attackPower();
        int roll = c.roll(ap[0], ap[1]);
        int power = roll + roll * (int) dmgTable[c.idx()] / 100;
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), power,
                DamageCalculator.SkillMods.withoutCrit());
        combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());
        log.info("[Skill] {} Vigor Ball p{}：面板掷 {} +{}% ⇒ {}", c.player().getName(), c.point(),
                roll, (int) dmgTable[c.idx()], power);
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Resurrection（T3.2）：概率原地救起死亡玩家 ────────────── */

    private List<HitTarget> resurrection(CastContext c) {
        double[] pctTable = c.table1d("Resurrection_Percent");
        if (pctTable == null || c.idx() >= pctTable.length) {
            log.error("[Skill] Resurrection 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        Player target = c.targetId() > 0 ? playerService.byId(c.targetId()) : null;
        PlayerEntity targetEnt = target != null ? playerService.entityOf(target) : null;
        if (target == null || targetEnt == null || !targetEnt.isDead()) {
            log.warn("[Skill] {} Resurrection p{}：目标 id={} 不是在场死亡玩家 ⇒ 不救"
                    + "（客户端尚无选尸能力，缺口登记）", c.player().getName(), c.point(), c.targetId());
            return List.of();
        }
        int chance = (int) pctTable[c.idx()];
        boolean hit = SkillCombat.randBetween(0, 99) < chance;
        if (hit && combatService.reviveInPlace(target)) {
            log.info("[Skill] {} Resurrection p{}：{} 被救起（{}% 成功）",
                    c.player().getName(), c.point(), target.getName(), chance);
        } else {
            // 原版失败就是静默（客户端照放视觉，目标原地不动）—— 这里只留日志
            log.info("[Skill] {} Resurrection p{}：{} {}% 未通过（原版静默失败）",
                    c.player().getName(), c.point(), target.getName(), chance);
        }
        return List.of();   // 复活不是伤害：零目标
    }

    /* ────────────── Extinction（T3.3）：160 圆内亡灵咒杀（当前生命% 真伤） ────────────── */

    private List<HitTarget> extinction(CastContext c) {
        double[] pctTable = c.table1d("Extinction_Percent");
        double[] amtTable = c.table1d("Extinction_Amount");
        if (pctTable == null || amtTable == null || c.idx() >= pctTable.length || c.idx() >= amtTable.length) {
            log.error("[Skill] Extinction 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        int chance = (int) pctTable[c.idx()] + c.player().getLevel() / 5;
        int amountPct = (int) amtTable[c.idx()];
        int killed = 0;
        List<HitTarget> hits = new ArrayList<>();
        for (Monster m : targets.circleAround(c.self(), 160f)) {
            if (m.getBrood() != Monster.Brood.UNDEAD) {
                continue;
            }
            if (SkillCombat.randBetween(0, 99) >= chance) {
                continue;
            }
            int life = (int) (m.getHp() * amountPct / 100);   // **当前生命**的 Amount%（:2657）
            DamageResult r = new DamageResult();
            r.setRawDamage(life);
            r.setFinalDamage(life);
            combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), false, false, false));
            killed++;
        }
        log.info("[Skill] {} Extinction p{}：咒杀 {}%（含等级/5）× 当前生命 {}%，范围内亡灵命中 {} 个",
                c.player().getName(), c.point(), chance, amountPct, killed);
        return hits;
    }

    /* ────────────── Virtual Life（T3.4）：限时减伤 buff（自/队友不对称刷新） ────────────── */

    private List<HitTarget> virtualLife(CastContext c) {
        double[] timeTable = c.table1d("Virtual_Life_Time");
        double[] pctTable = c.table1d("Virtual_Life_Percent");
        if (timeTable == null || pctTable == null || c.idx() >= timeTable.length || c.idx() >= pctTable.length) {
            log.error("[Skill] Virtual Life 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        int durationSec = (int) timeTable[c.idx()];
        int decPct = (int) pctTable[c.idx()];
        Player target = c.targetId() > 0 ? playerService.byId(c.targetId()) : null;
        if (target != null && target.getId() != c.player().getId()
                && playerService.entityOf(target) != null) {
            if (skillBuffStates.activeParam(target.getId(), c.skillId()) > 0) {
                log.info("[Skill] {} Virtual Life p{}：{} 的增益仍在生效 ⇒ 不刷新（源码如此）",
                        c.player().getName(), c.point(), target.getName());
                return List.of();
            }
            skillBuffStates.apply(target.getId(), c.skillId(), durationSec * 1000L, decPct);
            playerService.recalcPanel(target);   // sendPlayerStatus 内含 buff 条推送
            playerService.sendPlayerStatus(playerService.sessionOf(target), target);
            log.info("[Skill] {} Virtual Life p{}：队友 {} 上限+{}%、减伤 {}%，{} 秒",
                    c.player().getName(), c.point(), target.getName(), decPct, decPct, durationSec);
            return List.of();
        }
        skillBuffStates.apply(c.player().getId(), c.skillId(), durationSec * 1000L, decPct);
        playerService.recalcPanel(c.player());
        playerService.sendPlayerStatus(playerService.sessionOf(c.player()), c.player());   // 含 buff 条推送
        log.info("[Skill] {} Virtual Life p{}：自己上限+{}%、减伤 {}%，{} 秒",
                c.player().getName(), c.point(), decPct, decPct, durationSec);
        return List.of();
    }

    /* ────────────── Glacial Spike（T4.1）：身前矩形 AoE + 冰冻减速 ────────────── */

    private List<HitTarget> glacialSpike(CastContext c) {
        double[] dmgTable = c.table1d("Glacial_Spike_Damage");
        if (dmgTable == null || c.idx() >= dmgTable.length) {
            log.error("[Skill] Glacial Spike 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        // 朝向 = **由目标与施法者的坐标算出**（用户 2026-09-28 定）：原版对目标施法时
        // `lpChar->Angle.y = GetRadian2D(施法者pX/pZ, 目标pX/pZ)`（`SkillSub.cpp:3814`）——
        // 矩形指向"施法者→目标"。（背景：实体上的角度只在移动包里更新，原地转身不发移动包 ⇒
        // 2026-09-28 实测"走过去第一发中、原地转身第二发 0 命中"。）
        // 无目标/目标已死 ⇒ 退回客户端上报的施法朝向（caster_yaw）⇒ 实体最后已知朝向，逐级留痕。
        double yaw;
        Monster aim = c.targetId() > 0 ? targets.aliveMonster(c.targetId()) : null;
        if (aim != null) {
            yaw = Math.atan2(aim.getX() - c.self().getX(), aim.getZ() - c.self().getZ());
        } else {
            Float reportedYaw = c.casterYaw();
            if (reportedYaw != null) {
                yaw = reportedYaw;
            } else {
                log.warn("[Skill] Glacial Spike 无目标且起手包没带 caster_yaw（旧客户端/改包？）⇒ "
                        + "用实体最后已知朝向（最后一次移动的）代替 —— 朝向可能偏旧");
                yaw = c.self().getAngle();
            }
        }
        List<Monster> picked = targets.boxInFront(c.self(), yaw, 50, 340);
        if (picked.isEmpty()) {
            return List.of();
        }
        int[] ap = c.attackPower();
        int power = c.roll(ap[0], ap[1]);
        power += power * (int) dmgTable[c.idx()] / 100;
        // 减速：`time = 8` ⇒ **8 秒**、`SlowSpeed = 200`（对 256 的比例 = 78%）
        //（`Svr_Damge.cpp:1769-1776` 发包；客户端 `PlaySlowCount = Param2×70` 帧 @70fps，
        // `character.cpp:10740-10743` —— time 单位是**秒**；第一版我推成 1.83s ⇒ 效果不可感知）。
        // 原版对怪的减速在客户端；我们的怪服务端权威 ⇒ 服务端持态
        //（移动步长 / 攻击间隔 / 广播的动画速率三处消费）。
        long slowMs = 8_000L;
        List<HitTarget> hits = new ArrayList<>(picked.size());
        for (Monster m : picked) {
            DamageResult r = damageCalculator.calculatePlayerToMonsterAlwaysHit(
                    c.player(), m.combatStats(), power);
            combat.applyDamage(c.player(), c.self(), m, r, 0, c.skillId());
            m.applySlow(200, slowMs);
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
        }
        log.info("[Skill] {} Glacial Spike p{}：矩形命中 {} 个，面板掷 +{}% ⇒ {}，减速 {}% ×{}ms",
                c.player().getName(), c.point(), picked.size(), (int) dmgTable[c.idx()], power, 200 * 100 / 256, slowMs);
        return hits;
    }

    /* ────────────── Regeneration Field（T4.2）：再生场（数值应用在 RegenerationService） ────────────── */

    private List<HitTarget> regenerationField(CastContext c) {
        double[] timeTable = c.table1d("Regeneration_Field_Time");
        if (timeTable == null || c.idx() >= timeTable.length) {
            log.error("[Skill] Regeneration Field 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        int durationSec = (int) timeTable[c.idx()];
        skillBuffStates.apply(c.player().getId(), c.skillId(), durationSec * 1000L, c.point());
        buffStateService.push(playerService.sessionOf(c.player()), c.player());   // buff 条即时出现
        log.info("[Skill] {} Regeneration Field p{}：再生场 {} 秒（自己 +Life/Mana 全额，范围内队友魔法减半）",
                c.player().getName(), c.point(), durationSec);
        return List.of();
    }

    /* ────────────── Summon Muspell（T4.4）：被动守佑（招架 + 亡灵吸收） ────────────── */

    private List<HitTarget> summonMuspell(CastContext c) {
        double[] timeTable = c.table1d("Summon_Muspell_Time");
        if (timeTable == null || c.idx() >= timeTable.length) {
            log.error("[Skill] Summon Muspell 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        int durationSec = (int) timeTable[c.idx()];
        skillBuffStates.apply(c.player().getId(), c.skillId(), durationSec * 1000L, c.point());
        buffStateService.push(playerService.sessionOf(c.player()), c.player());   // buff 条即时出现
        log.info("[Skill] {} Summon Muspell p{}：守佑 {} 秒（招架 {}%，亡灵吸收 {}%）",
                c.player().getName(), c.point(), durationSec,
                (int) c.table1d("Summon_Muspell_BlockPercent")[c.idx()],
                (int) c.table1d("Summon_Muspell_UndeadAbsorbPercent")[c.idx()]);
        return List.of();
    }

    /* ────────────── 面板：伤害百分比（与结算同一张表） ────────────── */

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
        return null;
    }
}
