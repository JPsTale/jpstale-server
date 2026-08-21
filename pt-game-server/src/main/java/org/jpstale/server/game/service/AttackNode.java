package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 动作节点：攻击目标
 */
@Slf4j
public class AttackNode extends BehaviorNode {

    private final DamageCalculator damageCalculator;
    private final PlayerService playerService;
    private final CombatService combatService;

    public AttackNode(DamageCalculator damageCalculator, PlayerService playerService, CombatService combatService) {
        this.damageCalculator = damageCalculator;
        this.playerService = playerService;
        this.combatService = combatService;
    }

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        PlayerSession target = context.getTargetPlayer();
        if (target == null) {
            return NodeStatus.FAILURE;
        }

        // 检查攻击间隔
        long now = System.currentTimeMillis();
        if (now - monster.getLastAttackTime() < monster.getAttackSpeed()) {
            return NodeStatus.RUNNING;
        }

        Player player = playerService.getPlayer(target);
        if (player == null) {
            return NodeStatus.FAILURE;
        }

        // 执行攻击
        monster.setState(MonsterState.ATTACK);
        monster.setLastAttackTime(now);

        // 服务端权威伤害计算（含玩家防御/吸收/格挡）
        DamageResult result = damageCalculator.calculateMonsterToPlayer(monster, player);
        int newHp = Math.max(0, player.getHp() - result.getFinalDamage());
        player.setHp(newHp);
        target.setHp(newHp);

        log.debug("Monster {}#{} ATK {} dmg={} hp {}->{}",
            monster.getName(), monster.getId(), target.getCharacterId(),
            result.getFinalDamage(), newHp + result.getFinalDamage(), newHp);

        // 玩家死亡：回种族出生地重生（半血）
        if (newHp <= 0) {
            combatService.respawnPlayer(player);
            return NodeStatus.SUCCESS;
        }

        return NodeStatus.SUCCESS;
    }
}
