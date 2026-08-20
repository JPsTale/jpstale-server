package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 动作节点：攻击目标
 */
@Slf4j
public class AttackNode extends BehaviorNode {

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

        // 执行攻击
        monster.setState(MonsterState.ATTACK);
        monster.setLastAttackTime(now);

        // 对玩家造成伤害（调试工具用，未接伤害公式，直接用攻击力）
        int damage = Math.max(1, monster.getAttack());
        int newHp = target.getHp() - damage;
        target.setHp(Math.max(0, newHp));
        log.debug("Monster {}#{} ATK {} dmg={} hp {}->{}",
            monster.getName(), monster.getId(), target.getCharacterId(),
            damage, target.getHp() + damage, target.getHp());

        return NodeStatus.SUCCESS;
    }
}
