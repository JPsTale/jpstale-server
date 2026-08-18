package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GameMessageSender;
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

    private final Map<Long, Long> attackCooldowns = new ConcurrentHashMap<>();
    private static final long ATTACK_COOLDOWN_MS = 1000;

    /**
     * 玩家攻击怪物
     */
    public void playerAttackMonster(Player player, long monsterId, int skillId) {
        if (!checkAttackCooldown(player.getId())) {
            return;
        }

        Monster monster = findMonsterById(monsterId, player.getCurrentMapId());
        if (monster == null || !monster.isAlive()) {
            return;
        }

        DamageResult result = damageCalculator.calculatePlayerToMonster(player, monster, 0);

        if (result.isMissed()) {
            log.debug("Player {} missed monster {}", player.getName(), monster.getName());
            return;
        }

        monster.setHp(monster.getHp() - result.getFinalDamage());

        log.debug("Player {} hit monster {} for {} damage (crit={})",
            player.getName(), monster.getName(), result.getFinalDamage(), result.isCritical());

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

        // 发送死亡消息
        MessageProto.ServerMessage deathMsg = MessageProto.ServerMessage.newBuilder()
            .setMonsterDeath(MessageProto.S2C_MonsterDeath.newBuilder()
                .setMonsterId(monster.getId())
                .setKillerId(killer.getId())
                .setExp(exp)
                .setGold(gold)
                .build())
            .build();
        messageSender.sendToPlayer(killer.getId(), deathMsg);
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
}
