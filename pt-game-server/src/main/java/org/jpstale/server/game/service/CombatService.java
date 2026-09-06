package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<Long, Long> attackCooldowns = new ConcurrentHashMap<>();
    private static final long ATTACK_COOLDOWN_MS = 1000;

    /**
     * 报文入口：玩家普通攻击
     */
    @GamePacketHandler(MessageProto.ClientMessage.ATTACK_FIELD_NUMBER)
    public void handleAttack(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        Player player = playerService.getOrCreate(session);
        if (player == null) {
            return;
        }
        MessageProto.C2S_Attack attack = message.getAttack();
        playerAttackMonster(player, attack.getTargetId(), 0);
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
     * 玩家攻击怪物
     */
    public void playerAttackMonster(Player player, long monsterId, int skillId) {
        if (!checkAttackCooldown(player.getId())) {
            return;
        }

        Monster monster = findMonsterById(monsterId, player.getSession().getCurrentMapId());
        if (monster == null || !monster.isAlive()) {
            return;
        }

        DamageResult result = damageCalculator.calculatePlayerToMonster(player, monster, 0);

        if (result.isMissed()) {
            log.info("COMBAT {} attacks {}#{} -> MISS", player.getName(), monster.getName(), monsterId);
            return;
        }

        monster.setHp(monster.getHp() - result.getFinalDamage());

        log.info("COMBAT {} attacks {}#{} -> {} dmg (raw={} crit={}), hp {}/{}",
            player.getName(), monster.getName(), monsterId,
            result.getFinalDamage(), result.getRawDamage(), result.isCritical(),
            monster.getHp(), monster.getMaxHp());

        // 受击反击：怪物锁定攻击者（Evil 无目标时；Neutral 受击也反击）
        if (monster.getNature() == 0 || monster.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(monster, player.getSession(), player.getX(), player.getZ());
        }

        // 发送攻击结果给攻击者
        MessageProto.ServerMessage attackMsg = MessageProto.ServerMessage.newBuilder()
            .setAttackResult(MessageProto.S2C_AttackResult.newBuilder()
                .setAttackerId(player.getId())
                .setTargetId(monsterId)
                .setDamage(result.getFinalDamage())
                .setIsCritical(result.isCritical())
                .build())
            .build();
        messageSender.sendToPlayer(player.getId(), attackMsg);

        // 广播给附近玩家
        messageSender.broadcastToArea(player.getCurrentMapId(),
            player.getX(), player.getZ(), 50, attackMsg);

        // 检查怪物是否死亡
        if (monster.getHp() <= 0) {
            handleMonsterDeath(monster, player);
        }
    }

    /**
     * 处理怪物死亡
     */
    private void handleMonsterDeath(Monster monster, Player killer) {
        monster.onDeath();

        int exp = monster.getExp();
        killer.setExp(killer.getExp() + exp);

        int gold = monster.getGold();
        killer.setGold(killer.getGold() + gold);

        log.info("Monster {} killed by {}, exp={}, gold={}",
            monster.getName(), killer.getName(), exp, gold);

        // 升级检测：经验反算等级（对齐原版 GetLevelFromExp），每级 +5 自由属性点
        int newLevel = playerService.getLevelFromExp(killer.getExp());
        if (newLevel > killer.getLevel()) {
            int gained = (newLevel - killer.getLevel()) * 5;
            killer.setLevel(newLevel);
            killer.setStatePoint(killer.getStatePoint() + gained);
            playerService.recalcPanel(killer);
            // 同步会话等级（快照/状态栏用）
            killer.getSession().setLevel(newLevel);
            log.info("{} leveled up {} -> {} (+{} stat points, total {})",
                killer.getName(), newLevel - gained / 5, newLevel, gained, killer.getStatePoint());
            // 通知客户端升级（JSON，刷新面板）
            killer.getSession().sendText("{\"type\":\"game.levelUp\",\"data\":{\"level\":"
                + newLevel + ",\"statePoint\":" + killer.getStatePoint() + "}}");
        }

        // 权威落库：经验/金币/等级/属性点写回 characterinfo
        playerService.persistStats(killer);

        // 通知视野内观察者：击杀者带 exp/gold；其余只收死亡事件。尸体不保留（AOI 清出）。
        monsterAOI.onMonsterDeath(monster, killer.getId(), exp, gold);
    }

    private boolean checkAttackCooldown(long playerId) {
        long now = System.currentTimeMillis();
        Long lastAttack = attackCooldowns.get(playerId);
        if (lastAttack != null && now - lastAttack < ATTACK_COOLDOWN_MS) {
            return false;
        }
        attackCooldowns.put(playerId, now);
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
        int job = player.getJob();
        int mapId = job <= 4 ? 3 : 21;
        int[] start = mapRegionService.getStartPoint(mapId, 0, 0);
        int x = start != null ? start[0] : 0;
        int z = start != null ? start[1] : 0;
        int half = Math.max(1, player.getMaxHp() / 2);

        player.setHp(half);
        session.setHp(half);
        player.setX(x);
        player.setZ(z);
        session.setX(x);
        session.setZ(z);
        if (session.getCurrentMapId() != mapId) {
            aoiManager.onPlayerLeave(session);
            aoiManager.removePlayer(session);
            session.setCurrentMapId(mapId);
            aoiManager.addPlayer(session, x, z);
            aoiManager.onPlayerEnter(session);
        }
        log.info("Player {} died, respawn to map {} ({},{}) hp {}", player.getName(), mapId, x, z, half);
        // 通知前端：重新进入出生地图（半血）
        session.sendText("{\"type\":\"game.playerRespawn\",\"data\":{\"mapId\":"
            + mapId + ",\"x\":" + x + ",\"z\":" + z + ",\"hp\":" + half + "}}");
    }
}
