package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerMoveState;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.AttackSegment;
import org.jpstale.server.proto.base.C2S_AttackHit;
import org.springframework.context.annotation.Lazy;
import org.jpstale.server.proto.base.C2S_AttackStart;
import org.jpstale.server.proto.base.C2S_UseSkill;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_AttackPlan;
import org.jpstale.server.proto.base.S2C_AttackResult;
import org.jpstale.server.proto.base.S2C_AttackStart;
import org.jpstale.server.proto.base.S2C_PlayerDeath;
import org.jpstale.server.proto.base.S2C_PlayerRespawn;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.jpstale.server.common.codec.GameConstants.EXP_MODIFIER;

/**
 * 战斗服务 — 管理战斗流程
 */
@Slf4j
@Service
public class CombatService {

    @Autowired
    private DamageCalculator damageCalculator;

    @Autowired
    private EntityRegistry entityRegistry;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private MapRegionService mapRegionService;

    @Lazy
    @Autowired
    private AiEngine aiEngine;

    @Autowired
    private MonsterAOI monsterAOI;

    @Autowired
    private BattleLogService battleLogService;

    @Autowired
    private TeleportService teleportService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private org.jpstale.server.game.item.LootService lootService;

    @Autowired
    private org.jpstale.server.game.item.ItemRollService itemRollService;

    @Autowired
    private org.jpstale.server.game.item.GroundItemManager groundItems;

    private final Map<Long, Long> attackCooldowns = new ConcurrentHashMap<>();
    /** 攻击/死亡这类瞬时事件的广播半径（世界单位） */
    private static final float AOI_BROADCAST_RANGE = 50f;

    /** 每次攻击最多 4 段（原版 `EventFrame[0..3]`） */
    private static final int MAX_ATTACK_SEGMENTS = 4;

    /**
     * 技能下标 → `attackEffect`（即原版 `AttackEffect`：起手置 TRUE，`character.cpp:13354`；
     * 消费点 `:5166`（近战 EventAttack 命中特效）与 `:17712`（SendTransAttack → AttackState=2））。
     * 只影响**命中外观与武器音**（客户端唯一判定 `lookCritOf`），**不改伤害**。
     * 恰好只有 44 一条：Jumping Crash（= `SKILL_PLAY_JUMPING_CRASH`，技能下标 44，对应任务书
     * `docs/handoff/2026-09-21-枪兵一转三技能特效-任务书.md` §0.1-4）。
     * ⚠ Critical Hit **不置** AttackEffect —— 它的表现是 T1 曳光染色，不在这张表里。
     * 表外一律 false（技能当作普攻即时结算，见 `handleUseSkill` → `playerAttackMonster`）。
     */
    private static final Map<Integer, Boolean> SKILL_ATTACK_EFFECT = Map.of(44, true);

    /** 单段裁定结果（服务端掷出，随 S2C_AttackPlan 下发，命中帧按段消费） */
    private record PlannedSegment(boolean missed, boolean critical, int damage, boolean attackEffect) {}

    /** 一次攻击的计划：段结果 + 已结算段（幂等）+ 客户端序号（防错配） */
    private static final class AttackPlan {
        final int clientSeq;
        final long targetId;                      // 计划针对的目标 —— 命中帧的目标必须与它一致
        final List<PlannedSegment> segments;
        final Set<Integer> applied = new HashSet<>();
        AttackPlan(int clientSeq, long targetId, List<PlannedSegment> segments) {
            this.clientSeq = clientSeq;
            this.targetId = targetId;
            this.segments = segments;
        }
    }

    /** playerId → 当前攻击计划。起手时写入（覆盖旧的），命中帧按段消费。 */
    private final Map<Long, AttackPlan> attackPlans = new ConcurrentHashMap<>();

    /**
     * 玩家攻击距离 —— **直接用下发给客户端的同一个值**，不再本地另算一套。
     *
     * 那个值已经分好档（远程=装备射程 / 近战双手 60 / 近战单手与徒手 30），
     * 见 `PlayerStatCalculator.shootingRangeOf`。客户端 `selfAttackRange()` 读同一字段，
     * 所以面板显示、客户端停步判定、服务端距离校验三者不会漂移。
     *
     * ⚠ 调这两个档位前先看客户端：追击的**停步环半径**由攻击距离算出
     * （`src/game/combatRange.ts`，`min(32, 攻击距离×0.8)`），环必须**严格小于**射程。
     * 2026-09-15 把单手 40→30 时客户端环还是硬编码 32 ⇒ 空手/单手武器追到怪面前停住、
     * 既不攻击也不前进，900ms 后被判"寻路受阻"（客户端 `npm run verify-chase` 已闭环该不变量）。
     */
    private double attackRange(Player player) {
        return statCalculator.shootingRange(player);
    }

    /**
     * 报文入口：玩家起手（挥拳开始）——只做冷却/距离校验并广播，伤害在命中帧结算
     */
    @GamePacketHandler(ClientMessage.ATTACK_START_FIELD_NUMBER)
    public void handleAttackStart(PlayerSession session, ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        // 死亡躺下期间不能出拳（原版 SetMousePlay 对 DEAD 直接 return FALSE，点击无效）
        PlayerEntity dead = session.getEntity();
        if (dead != null && dead.isDead()) {
            return;
        }
        C2S_AttackStart req = message.getAttackStart();
        playerAttackStart(player, req.getTargetId(), req.getClientSeq(), req.getSegments(),
            req.getAnimIndex(), req.getAnimClip());
    }


    /**
     * 报文入口：命中帧（每段一次）——距离校验 + 结算该段伤害
     */
    @GamePacketHandler(ClientMessage.ATTACK_HIT_FIELD_NUMBER)
    public void handleAttackHit(PlayerSession session, ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        PlayerEntity dead = session.getEntity();
        if (dead != null && dead.isDead()) {
            return;   // 死亡躺下期间剩余段不再结算
        }
        C2S_AttackHit hit = message.getAttackHit();
        playerAttackHit(player, hit.getTargetId(), hit.getHitIndex());
    }

    /**
     * 报文入口：玩家使用技能（暂按普攻伤害处理，技能表后续接入）
     */
    @GamePacketHandler(ClientMessage.USE_SKILL_FIELD_NUMBER)
    public void handleUseSkill(PlayerSession session, ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        // 死亡躺下期间不能放技能（与 handleAttackStart/Hit 同一判据；此前只有攻击那两条有，漏了这条）
        PlayerEntity dead = session.getEntity();
        if (dead != null && dead.isDead()) {
            return;
        }
        C2S_UseSkill skill = message.getUseSkill();
        playerAttackMonster(player, skill.getTargetId(), skill.getSkillId());
    }

    /**
     * 起手（挥拳开始）：冷却 + 距离校验 → 广播 S2C_AttackStart（旁观者据此立刻挥拳 + 定挥拳时长）。
     * 不结算伤害；伤害由后续命中帧 C2S_AttackHit 触发。
     *
     * @param animIndex/animClip 攻击者**自己播的那一条**挥击动画（原样透传）。
     *   攻击动作的变体与长度随武器的单双手/类型而不同，旁观者必须播同一条才不会"一刀两种动作"。
     */
    public void playerAttackStart(Player player, long monsterId, int clientSeq, int declaredSegments,
                                  int animIndex, String animClip) {
        if (!checkAttackCooldown(player)) {
            log.info("COMBAT {} 起手 seq={} 被冷却拒绝（距上次 {}ms < 判定阈值 {}ms）→ 这次挥拳没有计划",
                player.getName(), clientSeq, msSinceLastAttack(player), attackGateMs(player));
            discardStalePlan(player, clientSeq);
            return;
        }
        PlayerSession session = player.getSession();
        PlayerEntity attackerEntity = session != null ? session.getEntity() : null;
        if (attackerEntity == null) {
            return;
        }
        Monster monster = findMonsterById(monsterId);
        if (monster == null || !monster.isAlive()) {
            log.info("COMBAT {} 起手 seq={} 目标 {} 不存在或已死 → 这次挥拳没有计划",
                player.getName(), clientSeq, monsterId);
            discardStalePlan(player, clientSeq);
            return;
        }
        // ⚠ 距离**不在这里拦截**：自机位置是客户端预测值、怪物位置是插值，边界附近两边必然不一致。
        //   若此处拒绝，这次挥拳就会落到命中帧的「无计划 → 重掷」兜底上，客户端按计划播的音与
        //   账本自相矛盾 —— 正是 B 方案要消除的东西。距离的唯一裁决点是命中帧 playerAttackHit。
        if (!inRange(player, attackerEntity, monster)) {
            log.info("COMBAT {} 起手 seq={} 距离超限（计划照发，伤害由命中帧裁决）",
                player.getName(), clientSeq);
        }
        // ── B 方案：**在这里就把各段结果裁定好**（而不是等命中帧），随 S2C_AttackPlan 下发。
        // 客户端因此可以在事件帧直接播正确的音（miss/暴击），无需等一次往返。
        // 段数由客户端声明（它知道自己的动画有几个非零 eventFrame），此处只做 1..4 截断；
        // 计划长度同时成为「合法段数上限」——命中帧超出即忽略（部分补齐 §8 的段数校验）。
        int segCount = Math.max(1, Math.min(MAX_ATTACK_SEGMENTS, declaredSegments));
        List<PlannedSegment> segs = new ArrayList<>(segCount);
        for (int i = 0; i < segCount; i++) {
            DamageResult roll = damageCalculator.calculatePlayerToMonster(player, monster, 0);
            segs.add(new PlannedSegment(roll.isMissed(), roll.isCritical(), roll.getFinalDamage(),
                false /* attackEffect：暂恒 false，等技能接入攻击链路后再填（任务书 T3，仅 Jumping Crash 44 → true） */));
        }
        attackPlans.put(player.getId(), new AttackPlan(clientSeq, monsterId, segs));
        if (session != null) {
            S2C_AttackPlan.Builder plan = S2C_AttackPlan.newBuilder()
                .setClientSeq(clientSeq)
                .setAttackerId(player.getId())
                .setTargetId(monsterId);
            for (int i = 0; i < segs.size(); i++) {
                PlannedSegment seg = segs.get(i);
                plan.addSegments(AttackSegment.newBuilder()
                    .setIndex(i).setMissed(seg.missed())
                    .setIsCritical(seg.critical()).setDamage(seg.damage())
                    .setAttackEffect(seg.attackEffect()));
            }
            session.send(ServerMessage.newBuilder().setAttackPlan(plan).build());
            // 同一份计划也发给旁观者：他们要在自己的事件帧播**这一段的正确结果音**
            // （miss 挥空 / 暴击追加）。否则只能等命中帧的 S2C_AttackResult，音效晚一个往返 ——
            // 而自机早已是"事件帧直接播正确音"，两边表现不一致。
            broadcastAttackPlan(attackerEntity, plan.build());
        }

        S2C_AttackStart start = S2C_AttackStart.newBuilder()
            .setAttackerId(player.getId())
            .setTargetId(monsterId)
            .setAttackSpeed(statCalculator.attackSpeed(player))
            .setAnimIndex(animIndex)
            .setAnimClip(animClip == null ? "" : animClip)
            .build();
        broadcastAttackStart(attackerEntity, start);
    }

    /**
     * 起手被拒时作废旧计划：客户端已经挥出这一拳（seq 比旧计划新），旧计划再留着，
     * 这一拳的命中帧就会被旧计划的**幂等去重**当成重复段吞掉 —— 挥了刀却没有伤害。
     * 只作废「比旧计划更新」的起手；seq 不大于旧计划的（重复/迟到报文）不动，避免误伤在飞的计划。
     */
    private void discardStalePlan(Player player, int clientSeq) {
        AttackPlan cur = attackPlans.get(player.getId());
        if (cur != null && clientSeq > cur.clientSeq) {
            attackPlans.remove(player.getId());
        }
    }

    /**
     * 命中帧（每段一次）：距离校验（不查冷却，冷却在起手）→ 按玩家攻击力结算一段伤害 → 广播。
     * hit_index 仅为段序号（0..3），不映射手（原版无此概念）。
     */
    public void playerAttackHit(Player player, long monsterId, int hitIndex) {
        PlayerSession session = player.getSession();
        PlayerEntity attackerEntity = session != null ? session.getEntity() : null;
        if (attackerEntity == null) {
            return;
        }
        Monster monster = findMonsterById(monsterId);
        if (monster == null || !monster.isAlive()) {
            // 目标已消失（被别人打死/离图）→ 这一刀落空。**不许静默**：客户端在事件帧已按计划播过
            // 一声命中音，必须回一条 missed 让它把那一刀的音收掉、改播挥空音（不然玩家只听到打击声、
            // 怪物头上却连伤害数字和 MISS 都没有）。
            log.info("COMBAT {} hit#{} 目标 {} 已不存在或已死 → 这一刀落空（回 MISS 修正客户端音效）",
                player.getName(), hitIndex, monsterId);
            battleLogService.playerWhiffed(player.getSession());
            reportWhiff(attackerEntity, player.getId(), monsterId, hitIndex);
            return;
        }
        if (!inRange(player, attackerEntity, monster)) {
            // 起手时距离就超限（计划照发了），命中帧仍然够不着 → 同样按落空处理
            log.info("COMBAT {} hit#{} 目标 {}#{} 已超出攻击距离 → 这一刀落空（回 MISS 修正客户端音效）",
                player.getName(), hitIndex, monster.getName(), monsterId);
            battleLogService.playerWhiffed(player.getSession());
            reportWhiff(attackerEntity, player.getId(), monsterId, hitIndex);
            return;
        }

        // ── B 方案：结果**取自起手时已裁定的计划**，不再在这里掷 ——
        // 客户端已按计划播了音，若此处重掷就会出现「听到暴击、账本说没有」的自相矛盾。
        AttackPlan plan = attackPlans.get(player.getId());
        boolean missed;
        boolean critical;
        int damage;
        if (plan != null) {
            if (hitIndex < 0 || hitIndex >= plan.segments.size()) {
                log.warn("COMBAT {} hit#{} seq={} 超出计划段数 {} → 忽略（不结算）",
                    player.getName(), hitIndex, plan.clientSeq, plan.segments.size());
                return;
            }
            if (plan.targetId != monsterId) {
                // 计划里的伤害是按**计划目标的防御**掷的 → 换目标消费会让伤害与目标不匹配
                log.warn("COMBAT {} hit#{} seq={} 目标 {} 与计划目标 {} 不一致 → 忽略",
                    player.getName(), hitIndex, plan.clientSeq, monsterId, plan.targetId);
                return;
            }
            if (!plan.applied.add(hitIndex)) {
                log.warn("COMBAT {} hit#{} seq={} 重复上报 → 忽略（幂等）",
                    player.getName(), hitIndex, plan.clientSeq);
                return;
            }
            PlannedSegment seg = plan.segments.get(hitIndex);
            missed = seg.missed();
            critical = seg.critical();
            damage = seg.damage();
        } else {
            // 无计划（旧客户端 / 计划丢失 / 起手本身就没通过）→ 退回即时裁定。**可见地降级，不静默**。
            // 排查起点：往上找同玩家的「起手 … → 这次挥拳没有计划」行，那里写了拒绝原因。
            log.warn("COMBAT {} hit#{} 无攻击计划 → 退回即时裁定（B 方案未覆盖该链路）",
                player.getName(), hitIndex);
            DamageResult result = damageCalculator.calculatePlayerToMonster(player, monster, 0);
            missed = result.isMissed();
            critical = result.isCritical();
            damage = result.getFinalDamage();
        }

        // 攻击结果（伤害/MISS 同一条广播，视野内全体可见 → 客户端飘字）。
        // broadcastToArea 已覆盖攻击者本人，无需再单独 sendToPlayer（否则重复扣血/飘字）。
        S2C_AttackResult.Builder ar = S2C_AttackResult.newBuilder()
            .setAttackerId(player.getId())
            .setTargetId(monsterId)
            .setDamage(damage)
            .setIsCritical(critical)
            .setHitIndex(hitIndex)
            .setAttackEffect(false);  // attackEffect：暂恒 false，等技能接入攻击链路后再填（T3，仅 44 Jumping Crash → true）
        if (missed) {
            log.info("COMBAT {} hit#{} seq={} {}#{} -> MISS", player.getName(), hitIndex,
                plan != null ? plan.clientSeq : -1, monster.getName(), monsterId);
            battleLogService.playerMissed(player.getSession(), monster.getName());
            broadcastAttackResult(attackerEntity, ar.setMissed(true).build());
            return;
        }
        ar.setMissed(false);

        monster.setHp(monster.getHp() - damage);

        log.info("COMBAT {} hit#{} seq={} {}#{} -> {} dmg (crit={}), hp {}/{}",
            player.getName(), hitIndex, plan != null ? plan.clientSeq : -1,
            monster.getName(), monsterId, damage, critical, monster.getHp(), monster.getMaxHp());
        battleLogService.playerDealtDamage(player.getSession(), monster.getName(), damage, critical);

        // 受击反击：怪物锁定攻击者（Evil 无目标时；Neutral 受击也反击）。坐标取实体
        if (monster.getNature() == 0 || monster.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(monster, attackerEntity, attackerEntity.getX(), attackerEntity.getZ());
        }

        broadcastAttackResult(attackerEntity, ar.build());

        if (monster.getHp() <= 0) {
            handleMonsterDeath(monster, player);
        }
    }

    /**
     * 命中帧判定为「没打中」（够不着 / 目标已消失）→ 给攻击者回一条 missed 结算。
     * 客户端据此把计划里那一刀播的命中音淡出、改播挥空音，并飘出 MISS —— 语义对齐原版
     * `AttackCritcal < 0`。**绝不静默丢弃**：静默会让客户端留着一声命中音，而怪物头上
     * 既没有伤害数字也没有 MISS（玩家看到的自相矛盾反馈）。
     */
    private void reportWhiff(PlayerEntity attackerEntity, long attackerId, long monsterId, int hitIndex) {
        S2C_AttackResult ar = S2C_AttackResult.newBuilder()
            .setAttackerId(attackerId)
            .setTargetId(monsterId)
            .setDamage(0)
            .setMissed(true)
            .setIsCritical(false)
            .setHitIndex(hitIndex)
            .build();
        broadcastAttackResult(attackerEntity, ar);
    }

    /** 距离校验：≤ 攻击距离（远程武器用射程） */
    private boolean inRange(Player player, PlayerEntity attacker, Monster monster) {        double dx = attacker.getX() - monster.getX();
        double dz = attacker.getZ() - monster.getZ();
        double range = attackRange(player);
        return dx * dx + dz * dz <= range * range;
    }

    private void broadcastAttackStart(PlayerEntity center, S2C_AttackStart start) {
        if (center == null) {
            return;
        }
        ServerMessage msg = ServerMessage.newBuilder()
            .setAttackStart(start)
            .build();
        messageSender.broadcastToArea(center.getMapId(),
            (float) center.getX(), (float) center.getZ(), 50, msg);
    }

    /**
     * 把攻击计划也广播给旁观者（同一份，含各段 miss/暴击）。
     * 攻击者本人已在前面单独收到过；重复收到无害（客户端按 clientSeq 校验自己那份）。
     */
    private void broadcastAttackPlan(PlayerEntity center, S2C_AttackPlan plan) {
        if (center == null) {
            return;
        }
        ServerMessage msg = ServerMessage.newBuilder()
            .setAttackPlan(plan)
            .build();
        messageSender.broadcastToArea(center.getMapId(),
            (float) center.getX(), (float) center.getZ(), 50, msg);
    }

    /**
     * 玩家攻击怪物（距离校验 + 攻速驱动冷却）
     */
    public void playerAttackMonster(Player player, long monsterId, int skillId) {
        if (!checkAttackCooldown(player)) {
            return;
        }

        PlayerSession session = player.getSession();
        PlayerEntity attackerEntity = session != null ? session.getEntity() : null;
        if (attackerEntity == null) {
            return;
        }
        Monster monster = findMonsterById(monsterId);
        if (monster == null || !monster.isAlive()) {
            return;
        }

        // 距离校验（≤ 攻击距离才结算，超距忽略；远程武器用其射程）
        double dx = attackerEntity.getX() - monster.getX();
        double dz = attackerEntity.getZ() - monster.getZ();
        double distSq = dx * dx + dz * dz;
        double range = attackRange(player);
        if (distSq > range * range) {
            return;
        }

        DamageResult result = damageCalculator.calculatePlayerToMonster(player, monster, 0);

        // 攻击结果（伤害/MISS 同一条广播，视野内全体可见 → 客户端飘字）。
        // 不再单独 sendToPlayer：broadcastToArea 已覆盖攻击者本人，重复发送会导致客户端重复扣血/飘字。
        S2C_AttackResult.Builder ar = S2C_AttackResult.newBuilder()
            .setAttackerId(player.getId())
            .setTargetId(monsterId)
            .setDamage(result.getFinalDamage())
            .setIsCritical(result.isCritical())
            .setHitIndex(0)
            .setAttackEffect(SKILL_ATTACK_EFFECT.getOrDefault(skillId, false));  // T3：按显式表填 attackEffect（44 Jumping Crash → true）
        if (result.isMissed()) {
            log.info("COMBAT {} attacks {}#{} -> MISS", player.getName(), monster.getName(), monsterId);
            battleLogService.playerMissed(player.getSession(), monster.getName());
            broadcastAttackResult(attackerEntity, ar.setMissed(true).build());
            return;
        }
        ar.setMissed(false);

        monster.setHp(monster.getHp() - result.getFinalDamage());

        log.info("COMBAT {} attacks {}#{} -> {} dmg (raw={} crit={}), hp {}/{}",
            player.getName(), monster.getName(), monsterId,
            result.getFinalDamage(), result.getRawDamage(), result.isCritical(),
            monster.getHp(), monster.getMaxHp());
        battleLogService.playerDealtDamage(player.getSession(), monster.getName(),
            result.getFinalDamage(), result.isCritical());

        // 受击反击：怪物锁定攻击者（Evil 无目标时；Neutral 受击也反击）。坐标取实体
        if (monster.getNature() == 0 || monster.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(monster, attackerEntity, attackerEntity.getX(), attackerEntity.getZ());
        }

        // 广播攻击结果给附近玩家（范围取实体坐标）
        broadcastAttackResult(attackerEntity, ar.build());

        // 检查怪物是否死亡
        if (monster.getHp() <= 0) {
            handleMonsterDeath(monster, player);
        }
    }

    private void broadcastAttackResult(PlayerEntity center, S2C_AttackResult ar) {
        ServerMessage attackMsg = ServerMessage.newBuilder()
            .setAttackResult(ar)
            .build();
        if (center == null) {
            return;
        }
        messageSender.broadcastToArea(center.getMapId(),
            (float) center.getX(), (float) center.getZ(), 50, attackMsg);
    }

    /**
     * 处理怪物死亡
     */
    private void handleMonsterDeath(Monster monster, Player killer) {
        monster.onDeath();

        // 注意，经验倍率应该是一个动态参数，由服务器管理员来设置基准倍率。如果有什么活动，可能会临时提高全服玩家的经验获取速度。
        // 玩家也可以使用经验道具来提升自己的经验倍率，组队也可能有额外的倍率提升。目前暂时以固定倍率计算，提高测试账号的升级速度。
        long exp = (long) (monster.getExp() * EXP_MODIFIER);
        killer.setExp(killer.getExp() + exp);

        // 掉落（对齐 EU OnSetDrop + HandleKill）：dropQuantity + premium/事件加成，逐次掷点
        int numDrops = Math.max(0, monster.getDropQuantity()) + lootService.extraDrops(killer);
        int gold = 0;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < numDrops; i++) {
            org.jpstale.server.game.item.LootService.DropResult dr = lootService.roll(monster.getTemplateId());
            if (dr == null || dr.type == org.jpstale.server.game.item.LootService.DropType.AIR) {
                continue;
            }
            if (dr.type == org.jpstale.server.game.item.LootService.DropType.GOLD) {
                gold += dr.gold;
                continue;
            }
            org.jpstale.server.game.item.ItemInstance item = itemRollService.rollByIdCode(dr.itemCode, null);
            if (item == null) {
                continue;
            }
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 0.3 + rnd.nextDouble() * 1.2;
            double gx = monster.getX() + Math.cos(ang) * dist;
            double gz = monster.getZ() + Math.sin(ang) * dist;
            double gy = mapRegionService.getHeight(monster.getMapId(), gx, gz);
            long ownerId = monster.isDropIsPublic() ? 0L : killer.getId();
            groundItems.add(item, monster.getMapId(), gx, gy, gz, ownerId, 0);
        }
        if (gold > 0) {
            // **金币是掉在地上的道具**（原版设计）：怪死掉一枚 Gold 物，玩家拾取时才入账
            //（原版 `SetInvenToItemInfo` → `sinPlusMoney` + `SIN_SOUND_COIN`，且**不入背包**）。
            // 我们过去直接 `killer.setGold(+gold)` —— 那样既没有拾取过程，也没有金币上限校验。
            // 金额来自该怪 `dropitem` 的 goldmin..goldmax 掷点（每只怪**一枚**，用户 2026-09-14 定）。
            org.jpstale.server.game.item.ItemInstance coin = itemRollService.rollByIdCode(
                org.jpstale.server.game.item.ItemRules.CODE_GOLD, null);
            if (coin == null) {
                // 不静默：数据缺了就说清哪一条缺、丢了多少
                log.error("[Drop] itemlist 里找不到金币道具（idcode=0x{}）：本次 {} 金币**未掉落**",
                    Integer.toHexString(org.jpstale.server.game.item.ItemRules.CODE_GOLD), gold);
            } else {
                double ang = rnd.nextDouble() * Math.PI * 2;
                double dist = 0.3 + rnd.nextDouble() * 1.2;
                double gx = monster.getX() + Math.cos(ang) * dist;
                double gz = monster.getZ() + Math.sin(ang) * dist;
                double gy = mapRegionService.getHeight(monster.getMapId(), gx, gz);
                long ownerId = monster.isDropIsPublic() ? 0L : killer.getId();
                groundItems.add(coin, monster.getMapId(), gx, gy, gz, ownerId, 0, gold);
            }
        }

        log.info("Monster {} killed by {}, exp={}, goldDrop={}（掉在地上，待拾取）",
            monster.getName(), killer.getName(), exp, gold);

        // 战斗日志：击杀 + 经验（金币不再于击杀时入账，故记 0 —— 拾取时另有记录）
        battleLogService.monsterKilled(killer.getSession(), monster.getName(), exp, 0);

        // 升级检测：经验反算等级（对齐原版 GetLevelFromExp），每级 +5 自由属性点
        int newLevel = playerService.getLevelFromExp(killer.getExp());
        if (newLevel > killer.getLevel()) {
            int gained = (newLevel - killer.getLevel()) * 5;
            killer.setLevel(newLevel);
            killer.setStatePoint(killer.getStatePoint() + gained);
            playerService.recalcPanel(killer);
            log.info("{} leveled up {} -> {} (+{} stat points, total {})",
                killer.getName(), newLevel - gained / 5, newLevel, gained, killer.getStatePoint());
            battleLogService.levelUp(killer.getSession(), newLevel, gained);
            // 通知客户端升级（JSON，刷新面板）
            killer.getSession().sendText("{\"type\":\"game.levelUp\",\"data\":{\"level\":"
                + newLevel + ",\"statePoint\":" + killer.getStatePoint() + "}}");
        }

        // 权威落库：经验/金币/等级/属性点写回 characterinfo
        playerService.persistStats(killer);

        // 经验/金币/等级变了必须**推给击杀者**：原先这里只写内存+落库，客户端没有任何通知 →
        // HUD 经验条与角色面板"打怪也不变动"（用户 2026-09-12 报）。一次 sendPlayerStatus 同时下发
        // S2C_PlayerState(HUD) + S2C_CharacterStatus(面板)。
        playerService.sendPlayerStatus(killer.getSession(), killer);

        // 通知视野内观察者：击杀者带 exp/gold；其余只收死亡事件。
        // 尸体**保留**：死怪留在 AOI 可见集里，直到 Monster.decayTime 到点后由主循环发 Disappear
        //（"死"与"消失"是两条独立事件；中途进场的观察者靠 S2C_MonsterAppear.dead 认出尸体）
        monsterAOI.onMonsterDeath(monster, killer.getId(), exp, gold);
    }

    /**
     * 攻击间隔公式（玩家，全站统一）：
     * frames = 60 − 3·clamp(as−6, 0, 6)   @60fps
     * as=0..6→1000ms  7→950  8→900  9→850  10→800  11→750  12+→700ms
     */
    static int attackIntervalMs(int attackSpeed) {
        int clamped = Math.clamp(attackSpeed - 6, 0, 6);
        int frames = 60 - 3 * clamped;               // 42..60
        return Math.round(frames * 1000f / 60f);       // 700..1000 ms
    }

    private boolean checkAttackCooldown(Player player) {
        int tolerated = attackGateMs(player);
        long now = System.currentTimeMillis();
        Long lastAttack = attackCooldowns.get(player.getId());
        if (lastAttack != null && now - lastAttack < tolerated) {
            return false;
        }
        attackCooldowns.put(player.getId(), now);
        return true;
    }

    /**
     * 起手冷却的**判定阈值** = 攻击间隔 × 90%。
     * 10% 冗余的理由：服务端是在**处理时刻**用墙钟计时，会带上 netty 事件循环的调度抖动（实测 ±80ms）；
     * 客户端起手闸门已是「间隔 + 67ms」，抖动与之同量级时会误拒本来合规的起手（实测约 1/30 次）。
     * 代价：客户端最多可超速约 11%。见 docs/design-player-combat.md §12.5（用户 2026-09-12 决定）。
     */
    private int attackGateMs(Player player) {
        int interval = attackIntervalMs(statCalculator.attackSpeed(player));
        return interval - interval / 10;
    }

    /** 距上次成功起手的毫秒数（无记录返回 -1）。冷却拒绝时写进日志，便于判断差了多少。 */
    private long msSinceLastAttack(Player player) {
        Long last = attackCooldowns.get(player.getId());
        return last == null ? -1 : System.currentTimeMillis() - last;
    }

    /**
     * 按**全局唯一 id** 找怪（id 来自 `EntityIdSource`，跨图唯一）。
     *
     * ⚠ 这里**不能**再按 `mapId` 过滤：可见性已统一为坐标口径（怪物 AOI 会用
     * `allMonsters()` 把边界另一侧的怪也推给玩家），若查找还按图，就会出现
     * "看得见、够得着，却打不到"的新坑。真正的门槛是调用处的 `inRange(...)` 距离判定。
     */
    private Monster findMonsterById(long monsterId) {
        return entityRegistry.findMonster(monsterId);
    }

    // ==================== 死亡与重生 ====================
    //
    // 原版出处（ex-machina 源码，路径见 AGENTS.md）：
    //   · 死后躺下、停在 DEAD 动画末帧 —— `playsub.cpp` 换装备分支：
    //       `if (dwMotionCode == CHRMOTION_STATE_DEAD) { SetMotionFromCode(DEAD); frame = (EndFrame-1)*160; }`
    //   · 死亡时不能点击/选怪 —— `Main.cpp SetMousePlay`：`MotionInfo->State == CHRMOTION_STATE_DEAD → return FALSE`
    //   · 三个选项 —— `Interface/sinInterFace.h`：`RESTART_FEILD=1 / RESTART_TOWN=2 / RESTART_EXIT=3`
    //   · 选项1 的落点 = **本图离尸体最近的 StartPoint** —— `Base/field.cpp sFIELD::GetStartPoint(x,z)` 遍历取最近
    //   · 死亡后不再被怪选中 —— 见 `PlayerEntity.isTargetable()`（原版行为：移出目标列表、重搜或回归）
    //
    // 代价为**用户定义**（2026-09-13）：选项1 = 本级跨度经验 10% + 金币 10%；选项2/3 = 本级跨度经验 1%，
    // 且**不掉级**（经验下限 = 本级起点，同原版 `DeadPlayerExp` 的 `exp64 < LowExp → LowExp`）。

    /** 强制复活等待（用户定义：1 分钟）；躺够时间自动按"村庄"复活 */
    private static final long RESPAWN_FORCE_MS = 60_000L;
    private static final int FIELD_EXP_PERCENT = 10;
    private static final int FIELD_GOLD_PERCENT = 10;
    private static final int TOWN_EXP_PERCENT = 1;

    /** 复活原因（下发给客户端，UI 据此显示不同提示） */
    private static final int REASON_FIELD = 1;
    private static final int REASON_TOWN = 2;
    private static final int REASON_FORCED = 3;

    /** 村庄复活点：坦普族(job1-4) → 理查登 ric(3)；魔灵族 → 菲尔拉 pilai(21) */
    private static final int TOWN_MAP_TEMPLE = 3;
    private static final int TOWN_MAP_PILAI = 21;

    /** 死亡中的玩家：playerId → 尸体所在地（选项1 要按它找最近的 StartPoint） */
    private final Map<Long, DeathSpot> deadPlayers = new ConcurrentHashMap<>();

    private record DeathSpot(int mapId, double x, double z, long atMs) {}

    /**
     * HP≤0 → **进入死亡态**（不再立刻复活）。
     *
     * 客户端据此播 DEAD 动画躺下、弹三个复活选项并倒计时；到期由 `tickDeaths` 强制送回村庄。
     * 怪物侧无需额外处理：`PlayerEntity.isTargetable()` 变为 false，AI 会自己移出目标并重搜。
     */
    public void enterDeath(Player player) {
        PlayerSession session = player.getSession();
        PlayerEntity entity = session != null ? session.getEntity() : null;
        if (entity == null) {
            return;
        }
        player.setHp(0);
        // 死亡是**角色级**状态（`Player.dead`），它才是 `isDead()` 的真值；
        // `moveState` 只是移动状态机（会被迟到的移动包覆盖，见 PlayerEntity.isDead 注释）。
        player.setDead(true);
        entity.setMoveState(PlayerMoveState.DEAD);
        deadPlayers.put(player.getId(), new DeathSpot(
            entity.getMapId(), entity.getX(), entity.getZ(), System.currentTimeMillis()));

        S2C_PlayerDeath death = S2C_PlayerDeath.newBuilder()
            .setPlayerId(player.getId())
            .setForceRespawnMs((int) RESPAWN_FORCE_MS)
            .build();
        // 广播（含自己）：旁观者也要看到躺下
        messageSender.broadcastToArea(entity.getMapId(), (float) entity.getX(), (float) entity.getZ(),
            AOI_BROADCAST_RANGE,
            ServerMessage.newBuilder().setPlayerDeath(death).build());
        log.info("COMBAT {} 死亡于 map {} ({},{}) —— 等待复活选择（{}s 后强制回村庄）",
            player.getName(), entity.getMapId(), (int) entity.getX(), (int) entity.getZ(),
            RESPAWN_FORCE_MS / 1000);
    }

    /** C2S_RespawnChoice 报文入口 */
    @GamePacketHandler(ClientMessage.RESPAWN_CHOICE_FIELD_NUMBER)
    public void handleRespawnChoicePacket(PlayerSession session, ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        handleRespawnChoice(player, message.getRespawnChoice().getChoice());
    }

    /**
     * 复活选择：1=附近重生点（10% 本级经验 + 10% 金币）/ 2=村庄（1% 本级经验）/ 3=继续躺（等强制）。
     * 非死亡态的请求一律忽略（重复/迟到包）。
     */
    public void handleRespawnChoice(Player player, int choice) {
        if (player == null) {
            return;
        }
        DeathSpot spot = deadPlayers.get(player.getId());
        if (spot == null) {
            log.info("COMBAT {} 收到复活选择 {} 但不在死亡态 → 忽略（重复/迟到包）", player.getName(), choice);
            return;
        }
        switch (choice) {
            case 1 -> respawnAtField(player, spot);
            case 2 -> respawnAtTown(player, REASON_TOWN);
            case 3 -> log.info("COMBAT {} 选择继续躺下 → {}s 后强制复活",
                player.getName(), RESPAWN_FORCE_MS / 1000);
            default -> log.warn("COMBAT {} 非法复活选项 {} → 忽略", player.getName(), choice);
        }
    }

    /** 每 tick：躺够 1 分钟的玩家强制送回村庄（代价同选项2） */
    public void tickDeaths(long nowMs) {
        if (deadPlayers.isEmpty()) {
            return;
        }
        Set<Long> online = new HashSet<>();
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session == null || !session.isPlaying()) {
                continue;
            }
            Player player = playerService.getPlayer(session);
            if (player == null) {
                continue;
            }
            online.add(player.getId());
            DeathSpot spot = deadPlayers.get(player.getId());
            if (spot == null || nowMs - spot.atMs() < RESPAWN_FORCE_MS) {
                continue;
            }
            log.info("COMBAT {} 躺满 {}s → 强制在村庄复活",
                player.getName(), RESPAWN_FORCE_MS / 1000);
            respawnAtTown(player, REASON_FORCED);
        }
        // 死在半路就断线/换角的玩家：记录要清掉，否则这张表只增不减（泄漏）
        deadPlayers.keySet().removeIf(id -> !online.contains(id));
    }

    /** 选项1：本图**离尸体最近**的 StartPoint（对齐 sFIELD::GetStartPoint 的取最近语义） */
    private void respawnAtField(Player player, DeathSpot spot) {
        int[] p = mapRegionService.getStartPoint(spot.mapId(), spot.x(), spot.z());
        if (p == null || p.length < 2) {
            // 该图没有 StartPoint 数据（我们 63 张图里有 24 张是 0 个）→ 回落到村庄复活。
            // 不套用原版的"地图中心"：中心可能在水里/怪堆里，比回城更糟（用户 2026-09-13 定）。
            log.info("COMBAT {} 选项1：map {} 无 StartPoint 数据 → 回落到村庄复活", player.getName(), spot.mapId());
            respawnAtTown(player, REASON_TOWN);
            return;
        }
        doRespawn(player, spot.mapId(), p[0], p[1], FIELD_EXP_PERCENT, FIELD_GOLD_PERCENT, REASON_FIELD);
    }

    /** 选项2/3：种族村庄出生点（不扣金币） */
    private void respawnAtTown(Player player, int reason) {
        // 落点交给 TeleportService.villageStartPoint（族判据 + 地形校验都在那里，唯一实现）：
        // 原先这里写 `job <= 4`（刺客/格斗家被误判成魔灵族）且不校验地形（点落在虚空就掉出地图）。
        int mapId = teleportService.villageMapId(player);
        int[] p = teleportService.villageStartPoint(player);
        if (p == null) {
            log.warn("COMBAT {} 复活失败：map {} 无有效村庄出生点", player.getName(), mapId);
            return;
        }
        doRespawn(player, mapId, p[0], p[1], TOWN_EXP_PERCENT, 0, reason);
    }

    /**
     * 三条路径共用：结算代价 → 传送到 (mapId,x,z) → 恢复状态 → 通知本人与旁观者。
     *
     * @param expPercent  扣本级跨度经验的百分比
     * @param goldPercent 扣金币的百分比
     */
    private void doRespawn(Player player, int mapId, int x, int z, int expPercent, int goldPercent, int reason) {
        PlayerSession session = player.getSession();
        deadPlayers.remove(player.getId());

        // ---- 代价：经验（下限 = 本级起点 → 不掉级）与金币 ----
        long expLoss = expLoss(player, expPercent);
        int goldLoss = (int) goldLoss(player, goldPercent);
        if (expLoss > 0) {
            long floor = playerService.getExpForLevel(player.getLevel());
            long next = Math.max(floor, player.getExp() - expLoss);
            player.setExp(next);
        }
        if (goldLoss > 0) {
            player.setGold(Math.max(0, player.getGold() - goldLoss));
        }

        int half = Math.max(1, player.getMaxHp() / 2);
        player.setHp(half);
        player.setDead(false);   // 解除死亡态（唯一判据，见 Player.dead）
        // 移动状态机也要复位：它只在收到移动上报时才被改写（MovementService.applyClientMove），
        // 不重置的话复活瞬间 `moveState` 还是 DEAD —— 旁观者看到"活人躺着"直到本人动一下。
        PlayerEntity ent = session != null ? session.getEntity() : null;
        if (ent != null) {
            ent.setMoveState(PlayerMoveState.IDLE);
        }

        // 搬人走唯一入口（TeleportService）：与脱困/传送门/卷轴共用同一份实现
        if (!teleportService.teleport(player, mapId, x, z, TeleportService.Reason.RESPAWN)) {
            log.warn("Player {} 复活失败：目标 map {} ({},{}) 不可用", player.getName(), mapId, x, z);
            return;
        }
        log.info("Player {} 复活（reason={}）→ map {} ({},{}) hp {} 代价: exp -{} / gold -{}",
            player.getName(), reason, mapId, x, z, half, expLoss, goldLoss);
        if (session != null) {
            // 死亡专属收尾：半血 + 关死亡面板。位置已由 S2C_PlayerTeleport 搬完（同一条广播两个受众），
            // 这里只补死亡语义（hp/max_hp + reason），所以不再重复 S2C_PlayerMove 广播。
            session.send(ServerMessage.newBuilder()
                    .setPlayerRespawn(S2C_PlayerRespawn.newBuilder()
                        .setPlayerId(player.getId())
                        .setMapId(mapId)
                        .setPosition(CommonProto.Position.newBuilder()
                            .setX(x).setY((float) Math.max(0, mapRegionService.getHeight(mapId, x, z)))
                            .setZ(z).build())
                        .setHp(half)
                        .setMaxHp(player.getMaxHp())
                        .setReason(reason)
                        .build())
                    .build());
            // HUD 的数字走权威状态（血/蓝/经验金币一起刷），否则面板停在死亡那一刻
            playerService.sendPlayerStatus(session, player);
            // 死亡/复活是低频且必须被看见的事件 → 系统频道（同升级），不并入战斗刷屏
            battleLogService.playerRespawned(session);
        }
    }

    /** 本级跨度经验 = expForLevel(level+1) - expForLevel(level) —— 死亡代价的分母 */
    private long levelSpanExp(Player player) {
        long lo = playerService.getExpForLevel(player.getLevel());
        long hi = playerService.getExpForLevel(player.getLevel() + 1);
        return Math.max(0, hi - lo);
    }

    private long expLoss(Player player, int percent) {
        return percent <= 0 ? 0 : levelSpanExp(player) * percent / 100;
    }

    private long goldLoss(Player player, int percent) {
        return percent <= 0 ? 0 : (long) player.getGold() * percent / 100;
    }
}
