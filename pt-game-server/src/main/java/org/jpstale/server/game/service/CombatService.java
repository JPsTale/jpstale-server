package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.MessageProto;
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
    private MonsterSpawnService monsterSpawnService;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private MapRegionService mapRegionService;

    @Autowired
    private AiEngine aiEngine;

    @Autowired
    private MonsterAOI monsterAOI;

    @Autowired
    private BattleLogService battleLogService;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private org.jpstale.server.game.item.LootService lootService;

    @Autowired
    private org.jpstale.server.game.item.ItemRollService itemRollService;

    @Autowired
    private org.jpstale.server.game.item.GroundItemManager groundItems;

    private final Map<Long, Long> attackCooldowns = new ConcurrentHashMap<>();
    private static final double MELEE_ATTACK_RANGE = 48.0;

    /** 每次攻击最多 4 段（原版 `EventFrame[0..3]`） */
    private static final int MAX_ATTACK_SEGMENTS = 4;

    /** 单段裁定结果（服务端掷出，随 S2C_AttackPlan 下发，命中帧按段消费） */
    private record PlannedSegment(boolean missed, boolean critical, int damage) {}

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
     * 玩家攻击距离：远程武器（射程>0，如弓）用其射程（对齐原版 Shooting_Range）；
     * 近战/徒手用固定近战距离。射程小于近战时取近战（防小射程武器反而更短）。
     */
    private double attackRange(Player player) {
        int sr = statCalculator.shootingRange(player);
        return sr > MELEE_ATTACK_RANGE ? sr : MELEE_ATTACK_RANGE;
    }

    /**
     * 报文入口：玩家起手（挥拳开始）——只做冷却/距离校验并广播，伤害在命中帧结算
     */
    @GamePacketHandler(MessageProto.ClientMessage.ATTACK_START_FIELD_NUMBER)
    public void handleAttackStart(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        MessageProto.C2S_AttackStart req = message.getAttackStart();
        playerAttackStart(player, req.getTargetId(), req.getClientSeq(), req.getSegments());
    }

    /**
     * 报文入口：命中帧（每段一次）——距离校验 + 结算该段伤害
     */
    @GamePacketHandler(MessageProto.ClientMessage.ATTACK_HIT_FIELD_NUMBER)
    public void handleAttackHit(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        MessageProto.C2S_AttackHit hit = message.getAttackHit();
        playerAttackHit(player, hit.getTargetId(), hit.getHitIndex());
    }

    /**
     * 报文入口：玩家使用技能（暂按普攻伤害处理，技能表后续接入）
     */
    @GamePacketHandler(MessageProto.ClientMessage.USE_SKILL_FIELD_NUMBER)
    public void handleUseSkill(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        MessageProto.C2S_UseSkill skill = message.getUseSkill();
        playerAttackMonster(player, skill.getTargetId(), skill.getSkillId());
    }

    /**
     * 起手（挥拳开始）：冷却 + 距离校验 → 广播 S2C_AttackStart（旁观者据此立刻挥拳 + 定挥拳时长）。
     * 不结算伤害；伤害由后续命中帧 C2S_AttackHit 触发。
     */
    public void playerAttackStart(Player player, long monsterId, int clientSeq, int declaredSegments) {
        if (!checkAttackCooldown(player)) {
            return;
        }
        PlayerSession session = player.getSession();
        PlayerEntity attackerEntity = session != null ? session.getEntity() : null;
        if (attackerEntity == null) {
            return;
        }
        Monster monster = findMonsterById(monsterId, attackerEntity.getMapId());
        if (monster == null || !monster.isAlive()) {
            return;
        }
        if (!inRange(player, attackerEntity, monster)) {
            return;
        }
        // ── B 方案：**在这里就把各段结果裁定好**（而不是等命中帧），随 S2C_AttackPlan 下发。
        // 客户端因此可以在事件帧直接播正确的音（miss/暴击），无需等一次往返。
        // 段数由客户端声明（它知道自己的动画有几个非零 eventFrame），此处只做 1..4 截断；
        // 计划长度同时成为「合法段数上限」——命中帧超出即忽略（部分补齐 §8 的段数校验）。
        int segCount = Math.max(1, Math.min(MAX_ATTACK_SEGMENTS, declaredSegments));
        List<PlannedSegment> segs = new ArrayList<>(segCount);
        for (int i = 0; i < segCount; i++) {
            DamageResult roll = damageCalculator.calculatePlayerToMonster(player, monster, 0);
            segs.add(new PlannedSegment(roll.isMissed(), roll.isCritical(), roll.getFinalDamage()));
        }
        attackPlans.put(player.getId(), new AttackPlan(clientSeq, monsterId, segs));
        if (session != null) {
            MessageProto.S2C_AttackPlan.Builder plan = MessageProto.S2C_AttackPlan.newBuilder()
                .setClientSeq(clientSeq)
                .setAttackerId(player.getId())
                .setTargetId(monsterId);
            for (int i = 0; i < segs.size(); i++) {
                PlannedSegment seg = segs.get(i);
                plan.addSegments(MessageProto.AttackSegment.newBuilder()
                    .setIndex(i).setMissed(seg.missed())
                    .setIsCritical(seg.critical()).setDamage(seg.damage()));
            }
            session.send(MessageProto.ServerMessage.newBuilder().setAttackPlan(plan).build());
        }

        MessageProto.S2C_AttackStart start = MessageProto.S2C_AttackStart.newBuilder()
            .setAttackerId(player.getId())
            .setTargetId(monsterId)
            .setAttackSpeed(statCalculator.attackSpeed(player))
            .build();
        broadcastAttackStart(attackerEntity, start);
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
        Monster monster = findMonsterById(monsterId, attackerEntity.getMapId());
        if (monster == null || !monster.isAlive()) {
            return;
        }
        if (!inRange(player, attackerEntity, monster)) {
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
                log.warn("COMBAT {} hit#{} 超出计划段数 {} → 忽略（不结算）",
                    player.getName(), hitIndex, plan.segments.size());
                return;
            }
            if (plan.targetId != monsterId) {
                // 计划里的伤害是按**计划目标的防御**掷的 → 换目标消费会让伤害与目标不匹配
                log.warn("COMBAT {} hit#{} 目标 {} 与计划目标 {} 不一致 → 忽略",
                    player.getName(), hitIndex, monsterId, plan.targetId);
                return;
            }
            if (!plan.applied.add(hitIndex)) {
                log.warn("COMBAT {} hit#{} 重复上报 → 忽略（幂等）", player.getName(), hitIndex);
                return;
            }
            PlannedSegment seg = plan.segments.get(hitIndex);
            missed = seg.missed();
            critical = seg.critical();
            damage = seg.damage();
        } else {
            // 无计划（旧客户端 / 计划丢失 / 起手被拒）→ 退回即时裁定。**可见地降级，不静默**。
            log.warn("COMBAT {} hit#{} 无攻击计划 → 退回即时裁定（B 方案未覆盖该链路）",
                player.getName(), hitIndex);
            DamageResult result = damageCalculator.calculatePlayerToMonster(player, monster, 0);
            missed = result.isMissed();
            critical = result.isCritical();
            damage = result.getFinalDamage();
        }

        // 攻击结果（伤害/MISS 同一条广播，视野内全体可见 → 客户端飘字）。
        // broadcastToArea 已覆盖攻击者本人，无需再单独 sendToPlayer（否则重复扣血/飘字）。
        MessageProto.S2C_AttackResult.Builder ar = MessageProto.S2C_AttackResult.newBuilder()
            .setAttackerId(player.getId())
            .setTargetId(monsterId)
            .setDamage(damage)
            .setIsCritical(critical)
            .setHitIndex(hitIndex);
        if (missed) {
            log.info("COMBAT {} hit#{} {}#{} -> MISS", player.getName(), hitIndex, monster.getName(), monsterId);
            broadcastAttackResult(attackerEntity, ar.setMissed(true).build());
            return;
        }
        ar.setMissed(false);

        monster.setHp(monster.getHp() - damage);

        log.info("COMBAT {} hit#{} {}#{} -> {} dmg (crit={}), hp {}/{}",
            player.getName(), hitIndex, monster.getName(), monsterId,
            damage, critical, monster.getHp(), monster.getMaxHp());

        // 受击反击：怪物锁定攻击者（Evil 无目标时；Neutral 受击也反击）。坐标取实体
        if (monster.getNature() == 0 || monster.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(monster, attackerEntity, attackerEntity.getX(), attackerEntity.getZ());
        }

        broadcastAttackResult(attackerEntity, ar.build());

        if (monster.getHp() <= 0) {
            handleMonsterDeath(monster, player);
        }
    }

    /** 距离校验：≤ 攻击距离（远程武器用射程） */
    private boolean inRange(Player player, PlayerEntity attacker, Monster monster) {
        double dx = attacker.getX() - monster.getX();
        double dz = attacker.getZ() - monster.getZ();
        double range = attackRange(player);
        return dx * dx + dz * dz <= range * range;
    }

    private void broadcastAttackStart(PlayerEntity center, MessageProto.S2C_AttackStart start) {
        if (center == null) {
            return;
        }
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setAttackStart(start)
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
        Monster monster = findMonsterById(monsterId, attackerEntity.getMapId());
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
        MessageProto.S2C_AttackResult.Builder ar = MessageProto.S2C_AttackResult.newBuilder()
            .setAttackerId(player.getId())
            .setTargetId(monsterId)
            .setDamage(result.getFinalDamage())
            .setIsCritical(result.isCritical())
            .setHitIndex(0);
        if (result.isMissed()) {
            log.info("COMBAT {} attacks {}#{} -> MISS", player.getName(), monster.getName(), monsterId);
            broadcastAttackResult(attackerEntity, ar.setMissed(true).build());
            return;
        }
        ar.setMissed(false);

        monster.setHp(monster.getHp() - result.getFinalDamage());

        log.info("COMBAT {} attacks {}#{} -> {} dmg (raw={} crit={}), hp {}/{}",
            player.getName(), monster.getName(), monsterId,
            result.getFinalDamage(), result.getRawDamage(), result.isCritical(),
            monster.getHp(), monster.getMaxHp());

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

    private void broadcastAttackResult(PlayerEntity center, MessageProto.S2C_AttackResult ar) {
        MessageProto.ServerMessage attackMsg = MessageProto.ServerMessage.newBuilder()
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
            killer.setGold(killer.getGold() + gold);
        }

        log.info("Monster {} killed by {}, exp={}, gold={}",
            monster.getName(), killer.getName(), exp, gold);

        // 战斗日志：击杀 + 经验/金币（进聊天窗"系统"tab）
        battleLogService.monsterKilled(killer.getSession(), monster.getName(), exp, gold);

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

        // 通知视野内观察者：击杀者带 exp/gold；其余只收死亡事件。尸体不保留（AOI 清出）。
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
        int interval = attackIntervalMs(statCalculator.attackSpeed(player));
        long now = System.currentTimeMillis();
        Long lastAttack = attackCooldowns.get(player.getId());
        if (lastAttack != null && now - lastAttack < interval) {
            return false;
        }
        attackCooldowns.put(player.getId(), now);
        return true;
    }

    private Monster findMonsterById(long monsterId, int mapId) {
        return monsterSpawnService.getMonstersByMap(mapId).stream()
            .filter(m -> m.getId() == monsterId)
            .findFirst()
            .orElse(null);
    }

    /**
     * 玩家死亡重生（对齐原版 record.cpp）：半血，回种族出生地
     * 坦普族(job1-4) → ric(3)，魔灵族 → pilai(21)；坐标用该地图出生点（安全区）
     */
    public void respawnPlayer(Player player) {
        PlayerSession session = player.getSession();
        PlayerEntity entity = session != null ? session.getEntity() : null;
        int job = player.getJob();
        int mapId = job <= 4 ? 3 : 21;
        int[] start = mapRegionService.getStartPoint(mapId, 0, 0);
        int x = start != null ? start[0] : 0;
        int z = start != null ? start[1] : 0;
        int half = Math.max(1, player.getMaxHp() / 2);

        player.setHp(half);

        // 坐标/地图权威在 PlayerEntity;跨图才做 AOI 摘除/重挂(无缝坐标下相邻仍可见,由 AOI 判断)
        if (entity != null) {
            boolean switchedMap = entity.getMapId() != mapId;
            entity.setX(x);
            entity.setZ(z);
            if (switchedMap) {
                entity.setMapId(mapId);
                aoiManager.onPlayerLeave(entity);
                aoiManager.removePlayer(entity);
                aoiManager.addPlayer(entity);
                aoiManager.onPlayerEnter(entity);
            }
        }
        log.info("Player {} died, respawn to map {} ({},{}) hp {}", player.getName(), mapId, x, z, half);
        // 通知前端：重新进入出生地图（半血）
        session.sendText("{\"type\":\"game.playerRespawn\",\"data\":{\"mapId\":"
            + mapId + ",\"x\":" + x + ",\"z\":" + z + ",\"hp\":" + half + "}}");
    }
}
