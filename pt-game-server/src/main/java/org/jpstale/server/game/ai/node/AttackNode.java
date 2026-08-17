package org.jpstale.server.game.ai.node;

import org.jpstale.server.game.ai.BehaviorNode;
import org.jpstale.server.game.ai.NodeStatus;
import org.jpstale.server.game.ai.AiContext;
import org.jpstale.server.game.monster.Monster;
import org.jpstale.server.game.monster.MonsterState;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 动作节点：攻击目标
 */
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

        // TODO: 计算伤害并发送给目标
        int damage = monster.getAttack();
        // target.send(攻击消息);

        return NodeStatus.SUCCESS;
    }
}
