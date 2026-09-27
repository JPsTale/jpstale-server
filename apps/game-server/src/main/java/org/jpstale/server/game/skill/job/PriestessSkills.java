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
 * <p>已迁 9 招（T1 全部 + T2 全部 + Chain Lightning；范围依据：rank 0 开 1..4、rank 1 开 5..8，
 * 转职/GM 提档后 T2 必须可用 —— 用户 2026-09-26 指示）：
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
 * </ul>
 *
 * <p><b>未迁（显式，走旧路）</b>：Vigor Ball / Resurrection / Extinction / Virtual Life / Glacial Spike /
 * Regeneration Field（维持型，U-08-8/9 未决）/ Summon Muspell（召唤系统）、5 转 4 个（无源码，
 * 服务端本就永久拒绝）。
 * <p><b>客户端缺口（服务端已就绪）</b>：技能施法的目标解析目前只认怪（`WorldView.beginSelfSkill`
 * 的 `monsterIdOfRoot`），Healing 治玩家目标要等客户端把玩家纳入技能瞄准。
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

    /* ────────────── Healing（T1.1）：治疗 —— 有目标治目标（角色：玩家或怪），没有治自己 ────────────── */

    /** 治疗距离门：原版 `GetSkillDistRange` 的 `case SKILL_HEALING: return 180 * fONE;`（`SkillSub.cpp:1233`） */
    private static final double HEAL_RANGE = 180;

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

        // 目标解析：**有目标就治目标**（原版 `SkillSub.cpp:2737` 的 `lpChar` 分支把治疗发给
        // **被选中的角色** —— 玩家或怪都行：`dm_SendTransDamage(lpChar, …)` → 服务端
        // `rsPlayHealing`（`OnSever.cpp:16478`）对任意 `smCHAR` 执行 `Life[0] += WParam` 并 clamp）。
        // 没有/无效的目标才治自己（`SkillSub.cpp:537` 那支自带 `!lpCharSelPlayer` 守卫）。
        // 距离门 = **原版 `GetSkillDistRange` 的 `case SKILL_HEALING: return 180 * fONE;`**
        // （`SkillSub.cpp:1233`；`playmain.cpp:2256` 用它判"是否进入射程"）——**不是武器射程**。
        // ⚠ 召唤物**不排除**：`playmain.cpp:2239-2245` 明确把"Healing 目标是召唤物"那面
        //    "不可攻击"的旗子清掉（`if (attack_UserMonster && CODE == SKILL_HEALING) attack_UserMonster = 0;`）。
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
                // ⚠ **我方选择**（原版无此路径）：原版 `rsPlayHealing` 只把回包发给**被治疗者自己的
                // socket**，而怪没有 socket ⇒ 若不做这一步，治怪在身上**没有任何可见反馈**。
                // 这里借用怪掉血的同一条 AOI 广播（`S2C_Recovery.targetId` = 怪 id，客户端
                // `applyUnitHp` 对 monsters 表同样生效）；仅为可见性，不参与任何判定。
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
            // **显式的"没有"**（AGENTS #12）：客户端说"治 X"但服务端认不出 X —— 原版
            // `rsPlayHealing`（`OnSever.cpp:16478`）这时就是 `return FALSE`（**什么都不做**），
            // 绝不改成"那就治自己"。此前这里默认回自己 ⇒ 症状是"我点玩家加血，日志写（自己）"，
            // 一个 id 空间错误被伪装成了正常行为（用户 2026-09-27 实测）。
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
        // 回复广播：飘字出现在**被治疗者**头上（原版把回包发给被治疗者的 socket，客户端就地显示）。
        if (healed > 0) {
            PlayerSession ts = playerService.sessionOf(target);
            if (ts != null) {
                playerService.sendPlayerStatus(ts, target);
                ts.send(ServerMessage.newBuilder()
                        .setRecovery(S2C_Recovery.newBuilder()
                                .setTargetId(target.getId())
                                .setHpAmount(healed)
                                .setCurrentHp(target.getHp())
                                .build())
                        .build());
            }
        }
        log.info("[Skill] {} Healing p{} → {}（{}）回复 {}（表 {}..{} + Power2 {}/3 + Spirit {}/8..6）",
                p.getName(), c.point(), target.getName(), self ? "自己" : "目标", healed,
                (int) heal2[c.idx()][0], (int) heal2[c.idx()][1], ap[0] - wd[0], spirit);
        return List.of();   // 治疗不是伤害：零目标（源码该 case 不走伤害结算）
    }

    /**
     * Healing 的目标解析（**唯一实现**，怪那一支在主函数里、这里只管玩家）。
     *
     * 语义（逐条对齐源码）：
     *   · `targetId == 0` ⇒ **治自己** —— 原版自疗分支（`SkillSub.cpp:537`）自带 `!lpCharSelPlayer` 守卫；
     *   · `targetId == 自己` ⇒ 治自己（对着自己点）；
     *   · 否则按 **charId** 找在场玩家（客户端上报的就是这个 id 空间，见下 ⚠）；
     *   · **对不上 ⇒ 返回 null = 什么都不做**（主函数显式警告并跳过）—— 对齐
     *     `rsPlayHealing`（`OnSever.cpp:16478`）找不到 serial 时的 `return FALSE`。
     *
     * ⚠ **id 空间**（2026-09-27 修，用户实测"点玩家加血，日志写（自己）"）：
     *   客户端认得的是 `S2C_PlayerAppear.playerId`，而服务端发的是 **charId**
     *   （`AOIManager` 的 `setPlayerId(e.getCharId())`）⇒ 客户端回报的 targetId 也是 charId。
     *   原先这里按 **运行时实体 id** 查（`entityByRuntimeId`，`PlayerEntity.getId()` 走 `EntityIdSource`，
     *   与 charId 解耦，见 `PlayerEntity` 类注释）⇒ **永远查不到** ⇒ 悄悄回自己。
     *   现在按 charId 查：`PlayerService.entities` 的键本就是 charId（`entityOf` 也是这么取）。
     */
    private Player resolvePlayerTarget(CastContext c) {
        long targetId = c.targetId();
        Player self = c.player();
        if (targetId <= 0 || targetId == self.getId()) {
            return self;
        }
        Player candidate = playerService.byId(targetId);
        if (candidate == null) {
            return null;   // 认不出 ⇒ 显式的"没有"（主函数警告），**不许**改成治自己
        }
        PlayerEntity candEntity = playerService.entityOf(candidate);
        if (candEntity == null) {
            // 装载着但**不在场上**（已离开地图/退出世界）⇒ 原版 `srFindCharFromSerial` 也找不到 ⇒ 不加
            log.warn("[Skill] {} Healing 目标 {} 不在场上（无实体）⇒ 本次不治疗",
                    self.getName(), candidate.getName());
            return null;
        }
        // ⚠ **不查地图**：原版 `rsPlayHealing` 只用 `srFindCharFromSerial` 在同一区域服务器里按
        // serial 找人，**没有任何地图判断**（我此前自造了一条"同图"，已按用户 2026-09-27 指示删除）。
        // 但"人已经躺下"要跳过：`rsPlayHealing` 只对 `smCharInfo.Life[0] > 0` 的角色加血
        // （`OnSever.cpp:16483`）——原版照旧回 TRUE 但不加，我们同样是"不加、也不改治自己"。
        if (candEntity.isDead()) {
            log.info("[Skill] {} Healing 目标 {} 已死亡 ⇒ 不加血（原版 `Life[0] > 0` 才加）",
                    self.getName(), candidate.getName());
            return candidate;
        }
        double dx = candEntity.getX() - c.self().getX();
        double dz = candEntity.getZ() - c.self().getZ();
        // 距离门同怪：原版 `GetSkillDistRange(SKILL_HEALING) = 180 * fONE`（**不是武器射程**）——
        // 玩家目标与怪目标走同一个门，两条路径不各写一份判据。
        // ⚠ 超距**不改成治自己**：原版的目标是客户端选定的那一个（`dwTarObjectSerial`），
        //   超距时 `playmain.cpp:2256` 那一侧根本不会起手；服务端不会把它换成别人。
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
        combat.applyDamage(c.player(), c.self(), m, r, 0);
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Multi Spark（T1.3）：N 道光芒，每道 = 一整次攻击（用户裁定） ────────────── */

    private List<HitTarget> multiSpark(CastContext c) {
        // 道数 = **起手时掷定**（`SkillCastService.begin`：rand(M_Spark_Num[p]/2+1, M_Spark_Num[p])，
        // 随 `S2C_SkillStart.spark_count` 下发给客户端视觉）—— 同一次施法结算与视觉共用一个 N。
        int sparks = c.sparkCount();
        if (sparks < 1) {
            // 正常链路不可能（begin 已掷定并校验）；走到这里 = 绕过起手的改包路径 ⇒ 不结算、留日志
            log.warn("[Skill] {} Multi Spark 事件帧没有起手道数 ⇒ 不结算", c.player().getName());
            return List.of();
        }
        Monster m = targets.single(c.player(), c.self(), c.targetId());
        if (m == null) {
            return List.of();
        }
        // ⚠ **2026-09-27 用户第二轮裁定（附实测日志）**：
        //   ① 每道光 = 1×攻击力（第一轮裁定，保留）；② **伤害是一次结算** —— 原版
        //      `Svr_Damge.cpp:3139-3150` 只算出一个 `Power` 交给 `dm_SendTransDamage` **一次**，
        //      不是 N 次独立伤害。逐道 applyDamage 会让一次技能触发 **N 次击杀检查**
        //      （实测：4 道 = 4 条 `Monster killed by prist`、4 份掉落 + 4 份经验）—— 用户抓出。
        //   ⇒ 现在：N 道的量**合并成一个总伤害**，一次命中/暴击/防御/吸收判定、一次扣血与死亡检查
        //      （掉落/经验只触发一次）。`M_Spark_Damage` 表与 +30% 仍不参与结算（第一轮裁定）。
        int[] ap = c.attackPower();
        int perBolt = c.roll(ap[0], ap[1]);      // 每道光 = 一倍攻击力（同一次掷点，N 道同值）
        int power = perBolt * sparks;            // 总伤害 = N × 攻击力
        DamageResult r = damageCalculator.calculatePlayerToMonster(c.player(), m.combatStats(), power);
        combat.applyDamage(c.player(), c.self(), m, r, 0);   // **一次落地**：一次死亡检查/奖励
        log.info("[Skill] {} Multi Spark p{} {} 道光（每道 {}）合计 {} 打 {}#{} —— 一次结算",
                c.player().getName(), c.point(), sparks, perBolt, power, m.getName(), c.targetId());
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

    /* ────────────── Holy Reflection（T2.3）：限时圣盾，亡灵攻击反弹 ────────────── */

    private List<HitTarget> holyReflection(CastContext c) {
        double[] timeTable = c.table1d("Holy_Reflection_Time");
        double[] retTable = c.table1d("Holy_Reflection_Return_Damage");
        if (timeTable == null || retTable == null || c.idx() >= timeTable.length || c.idx() >= retTable.length) {
            log.error("[Skill] Holy Reflection 参数表缺失（idx={}）", c.idx());
            return List.of();
        }
        // 自增益，不需要目标（`character.cpp:14010`：`SendProcessSKillToServer(SKILL_PLAY_HOLY_REFLECTION, point, 0, 0)`）；
        // 生效 = 窗口期内亡灵怪的攻击让攻击者吃反弹（`OnSever.cpp:34130-34131` 写 Time/Param 两字段）。
        int durationSec = (int) timeTable[c.idx()];
        int returnPct = (int) retTable[c.idx()];
        skillBuffStates.apply(c.player().getId(), c.skillId(), durationSec * 1000L, returnPct);
        log.info("[Skill] {} Holy Reflection p{} 圣盾 {} 秒，亡灵反弹 {}%",
                c.player().getName(), c.point(), durationSec, returnPct);
        return List.of();   // 增益不是伤害：零目标
    }

    /* ────────────── Grand Healing（T2.4）：一次掷点，治全队（不含自己） ────────────── */

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
        // 逐字 `Svr_Damge.cpp:3309-3313`（与 Healing 同族：表值 + Critical[1]/8..6 + Power2/3）：
        int min = (int) heal2[c.idx()][0] + (ap[0] - wd[0]) / 3 + spirit / 8;
        int max = (int) heal2[c.idx()][1] + (ap[1] - wd[1]) / 3 + spirit / 6;
        int amount = c.roll(min, max);

        // **只治队友**：`rsPlayGrandHealing` 的发送循环明确跳过施法者本人（`OnSever.cpp:16546`）；
        // 无队伍 = 无效果（源码整个包在 `if (dwPartyInfo && lpPartyMaster)` 里）。不按距离过滤（源码无此判断）。
        List<Player> members = partyService.membersOf(p.getId());
        int healed = 0;
        for (Player member : members) {
            if (member == p || member.getHp() <= 0) {
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
                // 原版把回包逐个发给成员自己的 socket（客户端就地飘字）—— 同构：直发，不广播区域
                ts.send(ServerMessage.newBuilder()
                        .setRecovery(S2C_Recovery.newBuilder()
                                .setTargetId(member.getId())
                                .setHpAmount(take)
                                .setCurrentHp(member.getHp())
                                .build())
                        .build());
            }
        }
        log.info("[Skill] {} Grand Healing p{} 全队治疗 {} 点 → {} 名队友（队伍 {} 人）",
                p.getName(), c.point(), amount, healed, members.size());
        return List.of();
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
     * 面板口径：只有 Holy Bolt 仍是"攻击力 ×(1+表值%)"模型 ⇒ 报表值。
     * **Multi Spark 已改模型（2026-09-26 用户裁定：N 道光、每道 = 1×攻击力）**⇒ 不再报百分比；
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
        return null;
    }
}
