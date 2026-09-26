package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.skill.SkillRules;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.skill.CastContext;
import org.jpstale.server.game.skill.HitTarget;
import org.jpstale.server.game.skill.JobSkills;
import org.jpstale.server.game.skill.JobSkillsCatalog;
import org.jpstale.server.game.skill.SegmentResult;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_SkillStart;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能施法的**生命周期编排**（设计文档 D6/D7；§9 P4）—— 校验、扣费、CD、段防重、起手广播。
 *
 * <p><b>两次上报的链路（用户 2026-09-24 指出此前的实现违背原版做法，已按 D7 重做）</b>：
 * 原版 PT 的攻击/技能是「客户端播动画与特效 + 上报意图 → **动画事件帧**才触发伤害，每次事件帧独立结算」
 * （`EventSkill` 那一侧）。所以本服务分两段：
 * <ol>
 *   <li>{@link #begin} —— 收到 `C2S_UseSkill`（意图）：职业门/已学门/迁入门/CD/MP 校验 → 扣 MP →
 *       **广播 {@code S2C_SkillStart}**（旁观者据此播同一条技能动画）→ 记下"待结算的施法"。
 *       此段**不结算任何伤害**。</li>
 *   <li>{@link #hit} —— 收到 `C2S_SkillHit`（事件帧回报）：按 `hit_index` 把**那一段**交给
 *       该职业的效果实现（{@code game.skill.job.*}）结算。每段独立（`Critical Hit` 的两段各掷各的）。</li>
 * </ol>
 * "哪一帧是事件帧"是**动画知识**，只在客户端（服务端没有动作数据）——AGENTS #14 同源。
 *
 * <p><b>结构与职责边界（2026-09-26 重构，用户裁定"一职业一套技能"）</b>：
 * <ul>
 *   <li>**本类只管生命周期**，与具体技能无关 —— 不随技能数增长（旧实现把逐技能
 *       {@code settleXxx} 与 4 处 {@code if (skillId==…)} 分派都堆在这里，已拆走）；</li>
 *   <li>**逐技能效果**在 {@code game.skill.job.*}（一职业一个类，EU 源码同款组织）；
 *       登记与分派唯一入口是 {@code JobSkillsCatalog}；</li>
 *   <li>**共用战斗件**（选敌/伤害落地/击退）在 {@code game.skill.combat.*}，全职业共享一份。</li>
 * </ul>
 *
 * <p>CD/熟练度/绑定表的下发辅助也在这里：它们是"施法"这件事的运行态，同样与具体技能无关。
 */
@Slf4j
@Service
public class SkillCastService {

    /** 待结算施法的有效期（ms）：起手广播后这段时间内收到的事件帧回报才认，超时作废。 */
    private static final long PENDING_TTL_MS = 3000;

    /** 单次施法最多结算的事件帧数（与普攻 MAX_ATTACK_SEGMENTS 同量级：原版 `EventFrame[0..3]`）。 */
    private static final int MAX_HIT_SEGMENTS = 4;

    @Autowired
    private SkillDataRegistry skillData;

    @Autowired
    private PlayerStatCalculator statCalculator;

    /** 职业效果类的唯一登记处（`knows` = 迁入门；`of(job)` = 事件帧结算的分派）。
     *  可为 null（单测直 new，与下方 skillData 同一惯例）。 */
    @Autowired
    private JobSkillsCatalog catalog;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    /** 熟练度增长（唯一实现 `SkillPointService.growMastery`） */
    @Autowired
    @Lazy
    private SkillPointService skillPoints;

    /** 起手的结果。 */
    public enum BeginResult {
        /** 已起手（校验通过、MP 已扣、起手广播已发）—— 后续等事件帧回报结算。 */
        STARTED,
        /** 该技能**尚未迁入**（对应职业类没有实现）⇒ 调用方保持旧路（当普攻即时结算）。 */
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
        if (!knowsMigrated(skillId)) {
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

    /** 该技能是否已迁入（对应职业类有实现）。catalog 未注入（单测直 new）⇒ 按未迁入。 */
    private boolean knowsMigrated(int skillId) {
        return catalog != null && catalog.knows(skillId);
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
        return SkillRules.cooldownMs(point, mastery, row.requireMastery());
    }

    /** 玩家离线：清掉他的 CD 计时与待结算（原版也不存 CD ⇒ 重登不延续）。 */
    public void clearPlayer(long playerId) {
        lastCastAt.remove(playerId);
        pending.remove(playerId);
        firedSegments.remove(playerId);
    }

    /* ────────────── ② 事件帧：逐段结算（效果在职业类，本类只校验与分派） ────────────── */

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
        // 起手记的 targetId 只用于校验技能/目标一致性（上面已校验技能），这里按回报的目标传下去。
        // 结算交给该职业的效果实现（选敌/算伤害/落地都在那边与 combat 包）；本类不认识任何具体技能。
        CastContext ctx = new CastContext(player, self, skillId, pc.level1Based(), targetId,
                skillData, statCalculator);
        JobSkills handler = catalog != null ? catalog.of(player.getJob()) : null;
        List<HitTarget> hits = handler == null ? null : handler.settle(ctx);
        if (hits == null) {
            // 有起手却无效果实现 ⇒ 正常链路到不了这里（begin 的迁入门挡了）＝改包路径；
            // 按"零目标"结算并留日志（源码语义：没实现的招不该有任何效果）。
            log.warn("[Skill] {} 事件帧 {}#{} 无效果实现（迁入门被绕过？）⇒ 零目标",
                    player.getName(), SkillKeys.describe(skillId), hitIndex);
            hits = List.of();
        }

        int mpCost = mpCostOf(skillId, pc.level1Based() - 1);
        log.info("[Skill] {} {} 事件帧 #{} 结算 {} 个目标",
                player.getName(), SkillKeys.describe(skillId), hitIndex, hits.size());
        return new SegmentResult(skillId, hitIndex, mpCost, hits);
    }

    /* ────────────── 成本与面板（生命周期侧的取数，均来自数据） ────────────── */

    /**
     * MP 消耗：表名来自**源码定义本身**（`SkillDefinition.useManaTable`，即 `[21] UseMana` 那格，
     * 不再维护手写映射）。`null` 表 = 源码写的是 0 ⇒ 成本 0（这是数据，不是兜底）；
     * 表在库里缺失/该等级没有值 ⇒ **-1（显式失败，不静默按 0）**。
     */
    private int mpCostOf(int skillId, int idx) {
        if (skillData == null || !skillData.hasId(skillId)) {
            return -1;   // 未注入（单测直 new）/ 身份表外：显式失败
        }
        String table = skillData.definition(skillData.byId(skillId).macro()).useManaTable();
        if (table == null) {
            return 0;   // 源码 [21] 写 0 ⇒ 无此表 = 免费
        }
        if (!skillData.hasTable(table)) {
            log.error("[Skill] MP 表缺失：{}（定义 [21] 写的就是它，但生成物里没有）", table);
            return -1;
        }
        if (idx >= skillData.tableLength(table)) {
            log.error("[Skill] MP 表 {} 在 idx={} 处没有值（源码把表写短了）", table, idx);
            return -1;
        }
        return (int) skillData.table1d(table)[idx];
    }

    /**
     * **面板用**：该技能该等级的"伤害加成百分比"区间（`{min,max}`；单一值时两者相等）。
     * `null` = 该技能**不是**"攻击力 ×(1+%)"模型（面板不显示伤害行，也不编一个数）。
     * 定义在各职业类的 {@link JobSkills#powerPct}（与结算同一张表），这里只做分派。
     *
     * @param point 1 基技能等级
     */
    public int[] powerPctOf(int skillId, int point) {
        JobSkills handler = catalog != null ? catalog.of(skillId >> 16) : null;
        return handler == null ? null : handler.powerPct(skillId, point);
    }
}
