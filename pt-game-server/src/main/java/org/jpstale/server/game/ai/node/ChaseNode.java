package org.jpstale.server.game.ai.node;

import org.jpstale.server.game.ai.BehaviorNode;
import org.jpstale.server.game.ai.NodeStatus;
import org.jpstale.server.game.ai.AiContext;
import org.jpstale.server.game.monster.Monster;
import org.jpstale.server.game.monster.MonsterState;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 动作节点：追击目标
 */
public class ChaseNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        PlayerSession target = context.getTargetPlayer();
        if (target == null) {
            return NodeStatus.FAILURE;
        }

        // 获取目标位置
        float targetX = context.getTargetX();
        float targetZ = context.getTargetZ();

        // 计算移动方向
        float dx = targetX - monster.getX();
        float dz = targetZ - monster.getZ();
        float distance = (float) Math.sqrt(dx * dx + dz * dz);

        if (distance <= 0.5f) {
            // 已到达目标位置
            return NodeStatus.SUCCESS;
        }

        // 移动向目标
        float moveDistance = Math.min(monster.getSpeed(), distance);
        float ratio = moveDistance / distance;
        monster.setX(monster.getX() + dx * ratio);
        monster.setZ(monster.getZ() + dz * ratio);
        monster.setState(MonsterState.CHASE);
        monster.setLastMoveTime(System.currentTimeMillis());

        return NodeStatus.RUNNING;
    }
}
