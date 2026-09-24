package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.skill.SkillRules;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_AttackResult;
import org.jpstale.server.proto.base.S2C_SkillStart;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 技能施法的**服务端权威编排**（设计文档 D6/D7；§9 P4）。
 *
 * <p><b>两次上报的链路（用户 2026-09-24 指出此前的实现违背原版做法，已按 D7 重做）</b>：
 * 原版 PT 的攻击/技能是「客户端播动画与特效 + 上报意图 → **动画事件帧**才触发伤害，每次事件帧独立结算」
 * （`EventSkill` 那一侧）。所以本服务分两段：
 * <ol>
 *   <li>{@link #begin} —— 收到 `C2S_UseSkill`（意图）：职业门/已学门/MP 校验 → 扣 MP →
 *       **广播 {@code S2C_SkillStart}**（旁观者据此播同一条技能动画）→ 记下"待结算的施法"。
 *       此段**不结算任何伤害**。</li>
 *   <li>{@link #hit} —— 收到 `C2S_SkillHit`（事件帧回报）：按 `hit_index` 结算**那一段**，
 *       每段独立（`Critical Hit` 的两段各掷各的）。</li>
 * </ol>
 * "哪一帧是事件帧"是**动画知识**，只在客户端（服务端没有动作数据）——AGENTS #14 同源。
 *
 * <p><b>逐技能的效果</b>（全部逐字照抄 `docs/技能系统-pikeman.md`，数值取生成物参数表）：
 * <ul>
 *   <li>{@link SkillIds#PIKE_WIND Pike Wind} —— 以己为中心圆 AoE、**必中**，
 *       伤害 = {@code rand(Pike_Wind_Damage[p][0..1])}（**表值直掷替换**攻击力），
 *       半径 = {@code Pike_Wind_Push_Lenght[p]}，命中者按 `AttackSize − 与施法者距离` 被**推离**；
 *       逐字 {@code SkillSub.cpp:105-129}（选敌）/ {@code Svr_Damge.cpp:2143-2172}（击退）。</li>
 *   <li>{@link SkillIds#CRITICAL_HIT Critical Hit} —— 单体 **2 段**（MotionLoop=2），
 *       每段暴击率 +{@code Critical_Hit_Critical[p]}；伤害走普攻公式。</li>
 *   <li>{@link SkillIds#JUMPING_CRASH Jumping Crash} —— 单体 1 段，伤害 = 攻击力 ×(1+表值%)，
 *       目标为恶魔系再 +30%（逐字 {@code Svr_Damge.cpp:2830-2836}；⚠ 30 不是 desc 的 100%）。</li>
 * </ul>
 *
 * <p><b>已知缺口（显式登记，不静默）</b>：技能 CD 未实现（公式依赖熟练度增长机制，P1 只存值）。
 */
@Slf4j
@Service
public class SkillCastService {

    /** 待结算施法的有效期（ms）：起手广播后这段时间内收到的事件帧回报才认，超时作废。 */
    private static final long PENDING_TTL_MS = 3000;

    /** 单次施法最多结算的事件帧数（与普攻 MAX_ATTACK_SEGMENTS 同量级：原版 `EventFrame[0..3]`）。 */
    private static final int MAX_HIT_SEGMENTS = 4;

    /** 武器族码（idcode 高 16 位）。 */
    private static final int FAMILY_AXE = 0x0101;
    private static final int FAMILY_SPEAR = 0x0105;
    private static final int FAMILY_SWORD = 0x0107;

    @Autowired
    private SkillDataRegistry skillData;

    @Autowired
    private DamageCalculator damageCalculator;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private EntityRegistry entityRegistry;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    /** 熟练度增长（唯一实现 `SkillPointService.growMastery`） */
    @Autowired
    @Lazy
    private SkillPointService skillPoints;

    @Autowired
    private BattleLogService battleLogService;

    /** 死亡入口（同包包私有共用） */
    @Autowired
    private CombatService combatService;

    /** 受击反击（与普攻同口径）；@Lazy 防 AI 与战斗的循环依赖 */
    @Lazy
    @Autowired
    private AiEngine aiEngine;

    /** 起手的结果。 */
    public enum BeginResult {
        /** 已起手（校验通过、MP 已扣、起手广播已发）—— 后续等事件帧回报结算。 */
        STARTED,
        /** 该技能**尚未迁入**本服务 ⇒ 调用方保持旧路（当普攻即时结算）。 */
        NOT_MIGRATED,
        /** 被拒绝（非本职业/未学/MP 不足）—— 什么都没发生。 */
        REJECTED,
        /** 被拒绝：**还在冷却里**（`GageLength < 35`）—— 调用方据此回一句可见原因（不静默）。 */
        REJECTED_COOLDOWN,
    }

    /** 待结算的施法（每次施法的运行态；`hit` 按它校验段序与目标）。 */
    private record PendingCast(int skillId, long targetId, int level1Based, long startMs) {}

    /** 每玩家至多一次待结算施法（新起手覆盖旧起手 —— 原版同一时刻也只有一个动作）。 */
    private final Map<Long, PendingCast> pending = new ConcurrentHashMap<>();

    /** 已结算过的段号（防同一事件帧重复回报；每次新起手清空）。 */
    private final Map<Long, List<Integer>> firedSegments = new ConcurrentHashMap<>();

    /**
     * 每玩家、每技能的**上次起手时刻**（毫秒）—— CD 判定用。
     *
     * <p>**只在内存**：原版也没把 CD 计量条存档（`record.cpp:525-526/634` 只存 `UseSkillCount`，
     * `GageLength` 是运行态）⇒ 重登后 CD 不延续，这里同样不落库。
     */
    private final Map<Long, Map<Integer, Long>> lastCastAt = new ConcurrentHashMap<>();

    /** AoE 命中者的结算明细（伤害/击退），供日志与测试。 */
    public record HitTarget(long monsterId, int damage, boolean critical, boolean missed, boolean knockedBack) {}

    /** 一段的结算结果。 */
    public record SegmentResult(int skillId, int hitIndex, int mpCost, List<HitTarget> hits) {}

    /* ────────────── ① 起手：校验 + 扣 MP + 起手广播（不结算） ────────────── */

    /**
     * 起手（收到 `C2S_UseSkill`）：校验 → 扣 MP → 广播 {@code S2C_SkillStart} → 登记待结算施法。
     *
     * @param animIndex 施法者**自己播的那一条**技能动作条目（原样透传给旁观者，AGENTS #14）
     */
    public BeginResult begin(Player player, int skillId, long targetId, int animIndex, String animClip) {
        if ((skillId >> 16) != player.getJob()) {
            log.info("[Skill] {} 起手 {} 拒绝：非本职业", player.getName(), Integer.toHexString(skillId));
            return BeginResult.REJECTED;
        }
        int point = player.getPropInt(SkillKeys.point(skillId));
        if (point < 1) {
            log.info("[Skill] {} 起手 {} 拒绝：未学（point=0）", player.getName(), Integer.toHexString(skillId));
            return BeginResult.REJECTED;
        }
        if (!isMigrated(skillId)) {
            return BeginResult.NOT_MIGRATED;   // 未迁入：调用方走旧路，本服务不碰
        }

        PlayerSession session = playerService.sessionOf(player);
        PlayerEntity self = session != null ? session.getEntity() : null;
        if (self == null) {
            return BeginResult.REJECTED;
        }

        // **CD 判定**（原版 `UseSkillFlag`：`GageLength >= 35` 才允许用；`sinSkill.cpp:2117/2124` 每帧刷）。
        // 我们是服务端权威：客户端那套计时只是显示与预判，改包绕不过这里。
        Long cd = cooldownMsOf(player, skillId);
        long now = System.currentTimeMillis();
        if (cd != null) {
            long left = cooldownLeftMs(player.getId(), skillId, cd, now);
            if (left > 0) {
                log.info("[Skill] {} 起手 {} 拒绝：冷却中（还需 {}ms / 共 {}ms）", player.getName(),
                        SkillKeys.describe(skillId), left, cd);
                return BeginResult.REJECTED_COOLDOWN;
            }
        }
        if (cd == null) {
            // 算不出 CD（该行没有 RequireMastery ⇒ 5 转那 60 行）⇒ **不编一个时长**，放行但留痕
            log.warn("[Skill] {} 起手 {} 的 CD 算不出来（该行无 RequireMastery）⇒ 本次不判 CD",
                    player.getName(), SkillKeys.describe(skillId));
        }

        int idx = point - 1;
        int mpCost = mpCostOf(skillId, idx);
        if (mpCost < 0) {
            log.error("[Skill] {} 起手 {} 失败：MP 表缺失", player.getName(), Integer.toHexString(skillId));
            return BeginResult.REJECTED;
        }
        if (player.getMp() < mpCost) {
            log.info("[Skill] {} 起手 {} 拒绝：MP 不足（需 {} 有 {}）",
                    player.getName(), SkillKeys.describe(skillId), mpCost, player.getMp());
            return BeginResult.REJECTED;
        }

        // MP 在**起手**扣（原版 sinCheckSkillUseOk 即在此扣）；伤害在事件帧结算
        player.setMp(player.getMp() - mpCost);
        playerService.sendPlayerStatus(session, player);

        // 登记待结算（新起手覆盖旧的：原版同一时刻只有一个动作）
        long pid = player.getId();
        // CD 的起点 = **服务端受理这一刻**（与扣 MP 同一时刻）；客户端也在收到 `S2C_SkillStart` 后才起表，
        // 于是客户端那圈弧总是**不早于**服务端的窗口结束 ⇒ 不会出现"客户端满了、服务端还在冷却"。
        recordCast(pid, skillId, now);
        pending.put(pid, new PendingCast(skillId, targetId, point, now));
        firedSegments.put(pid, new ArrayList<>());

        // 起手广播：旁观者立刻播**同一条**技能动画（自己已在本地播）
        messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder()
                        .setSkillStart(S2C_SkillStart.newBuilder()
                                .setCasterId(pid)
                                .setSkillId(skillId)
                                .setTargetId(targetId)
                                .setAnimIndex(animIndex)
                                .setAnimClip(animClip == null ? "" : animClip)
                                .setTargetPosition(CommonProto.Position.newBuilder()
                                        .setX((float) self.getX()).setY((float) self.getY()).setZ((float) self.getZ())))
                        .build());

        log.info("[Skill] {} 起手 {}（等级 {}）MP-{} anim={}",
                player.getName(), SkillKeys.describe(skillId), point, mpCost, animIndex);

        // **熟练度增长**（原版在技能用完之后调用，`Morayion.cpp:281-288`）：起手即算（起手已经是
        // "这一次放成功了"——后面的拒绝都发生在起手之前）。变了就落库 + 回推技能表，
        // 否则面板的熟练度条/HUD 的 CD 都要等到下一次推送才动。
        if (skillPoints.growMastery(player, skillId)) {
            playerService.persistStats(player);
            skillPoints.sendSkillTables(session, player);
        }
        return BeginResult.STARTED;
    }

    /** **记一次起手**（CD 的起点 = 服务端受理这一刻）。包级可见：测试直测，不必造会话。 */
    void recordCast(long playerId, int skillId, long nowMs) {
        lastCastAt.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(skillId, nowMs);
    }

    /**
     * 该玩家、该技能**还剩多少毫秒**冷却（0 = 可以放）；`cdMs` 由调用方算好传进来（便于直测）。
     * 语义 = 原版 `GageLength < 35` 那一段：起手后 `cdMs` 之内不许再放同一个技能。
     */
    long cooldownLeftMs(long playerId, int skillId, long cdMs, long nowMs) {
        Long last = lastCastAt.getOrDefault(playerId, Map.of()).get(skillId);
        if (last == null) {
            return 0;   // 没起过手 ⇒ 可以放（**不做登录后的强制冷却**：原版也不存计量条）
        }
        return Math.max(0, cdMs - (nowMs - last));
    }

    /**
     * 该玩家、该技能**此刻**的冷却时长（毫秒）；`null` = 算不出来（该行没有 `RequireMastery`）。
     *
     * <p>公式唯一实现在 {@link SkillRules#cooldownMs}；输入 = 当前等级 + **派生熟练度**
     * （`SkillRules.useSkillMastery`）⇒ 熟练度把 CD 压下来这件事在服务端算一次，
     * 客户端只拿到结果（`S2C_SkillList.skills[].cd_ms`）。
     */
    public Long cooldownMsOf(Player player, int skillId) {
        int point = player.getPropInt(SkillKeys.point(skillId));
        if (skillData == null || point < 1 || !skillData.hasId(skillId)) {
            return null;   // 未注入（单测直 new）/未学/不在身份表 ⇒ **算不出来**，不编时长
        }
        int mastery = SkillRules.useSkillMastery(player, skillData, skillId,
                statCalculator == null ? 0 : statCalculator.magicMastery(player));
        SkillDataRegistry.Skill row = skillData.byId(skillId);
        if ("NOT".equals(row.useCode())) {
            // **被动没有 CD**（不可施放 ⇒ 无所谓冷却；原版也不给它画计量条，`sinSkill.cpp:823`）。
            // 返回 null = "没有这个数"，由调用方按"无 CD"处理（不下发 0 之外的东西、也不判）
            return null;
        }
        Long ms = SkillRules.cooldownMs(point, mastery, row.requireMastery());
        // 上界 17500ms（Mastery=70 那一档）≪ int32 ⇒ 收窄是安全的；null 保持 null（显式未知）
        return ms;
    }

    /** 玩家离线：清掉他的 CD 计时与待结算（原版也不存 CD ⇒ 重登不延续）。 */
    public void clearPlayer(long playerId) {
        lastCastAt.remove(playerId);
        pending.remove(playerId);
        firedSegments.remove(playerId);
    }

    /* ────────────── ② 事件帧：逐段结算 ────────────── */

    /**
     * 事件帧回报（收到 `C2S_SkillHit`）：结算该段。
     *
     * <p>校验（**每段都过**）：有对应起手、技能与目标一致、段号未重复、在有效期内。
     * 校验失败**什么都不做**（记日志）—— 不静默按 0 结算。
     */
    public SegmentResult hit(Player player, int skillId, long targetId, int hitIndex) {
        long pid = player.getId();
        PendingCast pc = pending.get(pid);
        if (pc == null) {
            log.info("[Skill] {} 事件帧 {}#{} 无对应起手（超时/未起手）⇒ 忽略",
                    player.getName(), SkillKeys.describe(skillId), hitIndex);
            return null;
        }
        if (pc.skillId() != skillId) {
            log.info("[Skill] {} 事件帧技能不符：回报 {} 但当前起手是 {} ⇒ 忽略",
                    player.getName(), SkillKeys.describe(skillId), SkillKeys.describe(pc.skillId()));
            return null;
        }
        if (System.currentTimeMillis() - pc.startMs() > PENDING_TTL_MS) {
            pending.remove(pid);
            log.info("[Skill] {} 事件帧 {}#{} 超过有效期 {}ms ⇒ 忽略", player.getName(),
                    SkillKeys.describe(skillId), hitIndex, PENDING_TTL_MS);
            return null;
        }
        if (hitIndex < 0 || hitIndex >= MAX_HIT_SEGMENTS) {
            log.info("[Skill] {} 事件帧段号越界 {} ⇒ 忽略", player.getName(), hitIndex);
            return null;
        }
        List<Integer> fired = firedSegments.computeIfAbsent(pid, k -> new ArrayList<>());
        synchronized (fired) {
            if (fired.contains(hitIndex)) {
                return null;   // 同一段重复回报：忽略（防改包刷伤害）
            }
            fired.add(hitIndex);
        }

        PlayerSession session = playerService.sessionOf(player);
        PlayerEntity self = session != null ? session.getEntity() : null;
        if (self == null) {
            return null;
        }

        // 目标以**事件帧时点的现实**为准（原版 `lpCharSelPlayer` 语义：动作途中目标可能已死/走开）；
        // 起手记的 targetId 只用于校验技能/目标一致性（上面已校验技能），这里按回报的目标找。
        int idx = pc.level1Based() - 1;
        List<HitTarget> hits;
        if (skillId == SkillIds.PIKE_WIND.id()) {
            hits = settlePikeWind(player, self, idx);
        } else if (skillId == SkillIds.CRITICAL_HIT.id()) {
            hits = settleCriticalHit(player, self, targetId, idx);
        } else if (skillId == SkillIds.JUMPING_CRASH.id()) {
            hits = settleJumpingCrash(player, self, targetId, idx);
        } else {
            hits = List.of();
        }

        int mpCost = mpCostOf(skillId, idx);
        log.info("[Skill] {} {} 事件帧 #{} 结算 {} 个目标",
                player.getName(), SkillKeys.describe(skillId), hitIndex, hits.size());
        return new SegmentResult(skillId, hitIndex, mpCost, hits);
    }

    /** 该技能是否已迁入本服务（未迁入 ⇒ 调用方走旧路）。 */
    private static boolean isMigrated(int skillId) {
        return skillId == SkillIds.PIKE_WIND.id()
                || skillId == SkillIds.CRITICAL_HIT.id()
                || skillId == SkillIds.JUMPING_CRASH.id();
    }

    /* ────────────── Pike Wind：以己为中心 AoE + 必中 + 推离 ────────────── */

    private List<HitTarget> settlePikeWind(Player player, PlayerEntity self, int idx) {
        // Pike_Wind_Damage 是 **[10][2]**（min/max），必须走 table2d —— table1d 对二维表会直接抛
        double[][] dmg2 = skillData.table2d("Pike_Wind_Damage");
        double[] radiusTable = skillData.table1d("Pike_Wind_Push_Lenght");
        if (dmg2 == null || radiusTable == null || idx >= dmg2.length || idx >= radiusTable.length) {
            log.error("[Skill] Pike Wind 参数表缺失（idx={}）", idx);
            return List.of();
        }
        float radius = (float) radiusTable[idx];

        // 选敌：以己为中心的圆，同图、存活、非召唤物；**必中**（dm_SelectRange(…, FALSE)）
        List<Monster> targets = new ArrayList<>();
        for (Monster m : entityRegistry.allMonsters()) {
            if (!m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
                continue;
            }
            double dx = m.getX() - self.getX();
            double dz = m.getZ() - self.getZ();
            if (dx * dx + dz * dz <= (double) radius * radius) {
                targets.add(m);
            }
        }

        // 伤害 = **面板攻击力掷点 × (1 + 表值%)** —— ⚠ **我方决定，与源码不同**（用户 2026-09-24 裁定）：
        //   源码此招是 `lpTransSkillAttackData->Power = GetRandomPos(Pike_Wind_Damage[Point][0..1])`
        //   （`Svr_Damge.cpp:4393`）—— 用**自己的表覆盖**攻击力，1 级只有 3..20 ⇒ 比普攻还低
        //   （用户实测："普攻 40 点，技能只打 20 点"）。同族技能 Ground Pike/Roar/Mechanic Bomb/Spark
        //   也都是"自己的表覆盖"；**大多数技能**则是 `pow = GetRandomPos(包的 Power[0], Power[1])` +
        //   技能自己的百分比（包的 `Power[0..1]` = 玩家面板 `Attack_Damage`，`Damage.cpp:809-810`）。
        //   EU 库（`skilldbnew.skilldata`）给 Pike Wind 也是自己的表（15-25），即"太弱"是共识。
        //   我们按用户口径把表读作**百分比区间**（1 级 3..20%、10 级 21..80%），伤害随面板攻击力走。
        int[] ap = statCalculator.attackPower(player);
        int atk = randBetween(ap[0], ap[1]);
        int pct = randBetween(dmg2[idx][0], dmg2[idx][1]);
        int power = atk + atk * pct / 100;
        List<HitTarget> hits = new ArrayList<>(targets.size());
        for (Monster m : targets) {
            // **必中**（原版 `dm_SelectRange(x,y,z,range,FALSE)` ⇒ `dmUseAccuracy = 0`，`Damage.cpp:428/454`）
            // —— Pike Wind 不做命中判定；用户 2026-09-24 实测"MISS 了，跟原版不一样"⇒ 已改必中入口。
            DamageResult r = damageCalculator.calculatePlayerToMonsterAlwaysHit(player, m.combatStats(), power);
            applyDamage(player, self, m, r, radius);
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), !r.isMissed()));
        }
        return hits;
    }

    /* ────────────── Critical Hit：单体 1 段/次 + 暴击率加成（两段 = 两次事件帧） ────────────── */

    private List<HitTarget> settleCriticalHit(Player player, PlayerEntity self, long targetId, int idx) {
        double[] critTable = skillData.table1d("Critical_Hit_Critical");
        if (critTable == null || idx >= critTable.length) {
            log.error("[Skill] Critical Hit 参数表缺失（idx={}）", idx);
            return List.of();
        }
        Monster m = requireTarget(player, self, targetId);
        if (m == null) {
            return List.of();
        }
        int critBonus = (int) critTable[idx];
        DamageResult r = damageCalculator.calculatePlayerToMonster(player, m.combatStats(), 0, critBonus);
        applyDamage(player, self, m, r, 0);
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── Jumping Crash：单体 1 段 + Power 百分比 + 恶魔加成 ────────────── */

    private List<HitTarget> settleJumpingCrash(Player player, PlayerEntity self, long targetId, int idx) {
        double[] dmgTable = skillData.table1d("Jumping_Crash_Damage");
        if (dmgTable == null || idx >= dmgTable.length) {
            log.error("[Skill] Jumping Crash 参数表缺失（idx={}）", idx);
            return List.of();
        }
        Monster m = requireTarget(player, self, targetId);
        if (m == null) {
            return List.of();
        }
        // 伤害 = **攻击力掷点** ×(1 + 表值%)（原版 `Power += Power*Jumping_Crash_Damage[Point]/100`；
        // 包里的 `Power` 是玩家攻击力，不是武器原始伤害）。
        // ⚠ 2026-09-24 实测修：此前用 `baseAttack`（**武器原始伤害** 3-5 那种）当基数 ⇒
        //   55% 加成后仍只有个位数伤害（用户报"固定 9 点"）。面板同源的攻击力区间是 `attackPower`。
        int[] ap = statCalculator.attackPower(player);
        int power = randBetween(ap[0], ap[1]);
        int boosted = power + power * (int) dmgTable[idx] / 100;
        // 恶魔系 +30%（Svr_Damge.cpp:2834 逐字；⚠ 30 不是 desc 写的 100）
        if (m.getBrood() == Monster.Brood.DEMON) {
            boosted += boosted * 30 / 100;
        }
        // **施法前临时加命中**（原版把 Attack_Rating 按百分比放大、发包后还原，`SkillSub.cpp:1935-1943`）：
        // 我们服务端权威 ⇒ 判定时放大同样比例（不改玩家状态，"还原"天然成立）。
        int accBonus = accuracyBonusOf(idx);
        DamageResult r = damageCalculator.calculatePlayerToMonster(player, m.combatStats(), boosted,
                new DamageCalculator.SkillMods(accBonus, 0));
        applyDamage(player, self, m, r, 0);
        log.info("[Skill] {} Jumping Crash p{} power={} boosted={} 打 {}#{}（brood={}）",
                player.getName(), idx + 1, power, boosted, m.getName(), targetId, m.getBrood());
        return List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
    }

    /* ────────────── 共用件 ────────────── */

    /**
     * **面板用**：该技能该等级的"伤害加成百分比"区间（`{min,max}`；单一值时两者相等）。
     *
     * `null` = 该技能**不是**"攻击力 ×(1+%)"模型（面板不显示伤害行，也不编一个数）。
     * 这里是各技能伤害模型的**唯一定义处**（与 `settleXxx` 用同一张表），面板只是读它。
     *
     * @param point 1 基技能等级
     */
    public int[] powerPctOf(int skillId, int point) {
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

    /** Jumping Crash 的"施法前临时加命中"表值（`Jumping_Crash_Attack_Rating[10] = {10,20,…,65}`，`sinSkill_Info.cpp:193`）。 */
    private int accuracyBonusOf(int idx) {
        double[] t = skillData.table1d("Jumping_Crash_Attack_Rating");
        if (t == null || idx >= t.length) {
            log.error("[Skill] 命中加成表缺失：Jumping_Crash_Attack_Rating（idx={}）", idx);
            return 0;
        }
        return (int) t[idx];
    }

    /** 技能的 MP 表名（每个技能一张 `*_UseMana`）。 */
    private static String mpTableOf(int skillId) {
        if (skillId == SkillIds.PIKE_WIND.id()) return "Pike_Wind_UseMana";
        if (skillId == SkillIds.CRITICAL_HIT.id()) return "Critical_Hit_UseMana";
        if (skillId == SkillIds.JUMPING_CRASH.id()) return "Jumping_Crash_UseMana";
        return null;
    }

    /** MP 表值（`<技能>_UseMana[point]`）；表缺失 ⇒ -1（显式失败，不静默按 0）。 */
    private int mpCostOf(int skillId, int idx) {
        String table = mpTableOf(skillId);
        if (table == null) {
            return -1;
        }
        double[] t = skillData.table1d(table);
        if (t == null || idx >= t.length) {
            log.error("[Skill] MP 表缺失：{}", table);
            return -1;
        }
        return (int) t[idx];
    }

    /** 单目标校验：存在/存活/非召唤物/同图/距离（≤ 武器射程）。 */
    private Monster requireTarget(Player player, PlayerEntity self, long targetId) {
        Monster m = entityRegistry.findMonster(targetId);
        if (m == null || !m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
            log.info("[Skill] {} 目标 {} 不可用（不存在/已死/召唤物/异图）", player.getName(), targetId);
            return null;
        }
        double dx = self.getX() - m.getX();
        double dz = self.getZ() - m.getZ();
        double range = attackRangeOf(player);
        if (dx * dx + dz * dz > range * range) {
            log.info("[Skill] {} 目标 {} 超距（>{}）", player.getName(), targetId, range);
            return null;
        }
        return m;
    }

    /** 攻击距离（武器射程）：与 `CombatService.attackRange` 同一实现（走便捷方法，别各写一份取值链）。 */
    private double attackRangeOf(Player player) {
        return statCalculator.shootingRange(player);
    }

    /**
     * 结算一条伤害：扣血/死亡 + S2C_AttackResult 广播 + 仇恨（与普攻同口径）。
     *
     * @param knockbackDist 推离距离（世界单位）= 原版 `AttackSize`（= `Pike_Wind_Push_Lenght`）；
     *                      0 = 不推。
     */
    private void applyDamage(Player player, PlayerEntity self, Monster m, DamageResult r, float knockbackDist) {
        S2C_AttackResult.Builder ar = S2C_AttackResult.newBuilder()
                .setAttackerId(player.getId())
                .setTargetId(m.getId())
                .setDamage(r.getFinalDamage())
                .setIsCritical(r.isCritical())
                .setHitIndex(0);
        if (r.isMissed()) {
            ar.setMissed(true);
            broadcastResult(self, ar);
            return;
        }
        ar.setMissed(false);
        m.setHp(m.getHp() - r.getFinalDamage());
        battleLogService.playerDealtDamage(playerService.sessionOf(player), m.getName(),
                r.getFinalDamage(), r.isCritical());

        // 受击反击（Evil 无目标时；Neutral 受击也反击）——与普攻同口径
        if (m.getNature() == 0 || m.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(m, self, self.getX(), self.getZ());
        }

        // 推离（AttackState=1），逐字 `Svr_Damge.cpp:2143-2172`：
        //   ang2 = 怪→施法者 的角；ang = ang2+180°（转身背对玩家）；MoveAngle(dist)；再转回来。
        //   dist = AttackSize − 与施法者的水平距离 ⇒ **越近推得越远**；已在范围外（≤0）不推。
        //   两条门：|Δy|>100 或水平距>800 ⇒ 不推。
        // 方向 = **远离**施法者（`-dx/-dz` = 从施法者指向怪）。
        if (knockbackDist > 0 && m.isAlive()) {
            double dx = self.getX() - m.getX();
            double dy = self.getY() - m.getY();
            double dz = self.getZ() - m.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dy) <= 100 && horizontal <= 800) {
                double dist = knockbackDist - horizontal;
                if (dist > 0) {
                    m.moveTo(m.getX() - dx, m.getY(), m.getZ() - dz, dist);
                }
            }
        }

        if (!m.isAlive()) {
            combatService.handleMonsterDeath(m, player);
        }
        broadcastResult(self, ar);
    }

    private void broadcastResult(PlayerEntity self, S2C_AttackResult.Builder ar) {
        messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder().setAttackResult(ar).build());
    }

    /** [min,max] 含端点随机（原版 GetRandomPos）。 */
    private static int randBetween(double min, double max) {
        int lo = (int) Math.round(min);
        int hi = (int) Math.round(max);
        return lo + ThreadLocalRandom.current().nextInt(Math.max(1, hi - lo + 1));
    }
}
