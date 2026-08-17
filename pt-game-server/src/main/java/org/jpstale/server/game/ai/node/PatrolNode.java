package org.jpstale.server.game.ai.node;

import org.jpstale.server.game.ai.BehaviorNode;
import org.jpstale.server.game.ai.NodeStatus;
import org.jpstale.server.game.ai.AiContext;
import org.jpstale.server.game.monster.Monster;
import org.jpstale.server.game.monster.MonsterState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 动作节点：随机巡逻
 */
public class PatrolNode extends BehaviorNode {

    private static final float PATROL_RANGE = 50.0f;
    private static final float PATROL_SPEED = 2.0f;

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        // 如果没有巡逻目标，生成一个随机目标
        if (context.getPatrolX() == 0 && context.getPatrolZ() == 0) {
            context.setPatrolX(monster.getX() + ThreadLocalRandom.current().nextFloat() * PATROL_RANGE * 2 - PATROL_RANGE);
            context.setPatrolZ(monster.getZ() + ThreadLocalRandom.current().nextFloat() * PATROL_RANGE * 2 - PATROL_RANGE);
        }

        // 计算移动方向
        float dx = context.getPatrolX() - monster.getX();
        float dz = context.getPatrolZ() - monster.getZ();
        float distance = (float) Math.sqrt(dx * dx + dz * dz);

        if (distance <= 1.0f) {
            // 到达巡逻目标，生成新的巡逻目标
            context.setPatrolX(monster.getX() + ThreadLocalRandom.current().nextFloat() * PATROL_RANGE * 2 - PATROL_RANGE);
            context.setPatrolZ(monster.getZ() + ThreadLocalRandom.current().nextFloat() * PATROL_RANGE * 2 - PATROL_RANGE);
            return NodeStatus.SUCCESS;
        }

        // 移动向巡逻目标
        float moveDistance = Math.min(PATROL_SPEED, distance);
        float ratio = moveDistance / distance;
        monster.setX(monster.getX() + dx * ratio);
        monster.setZ(monster.getZ() + dz * ratio);
        monster.setState(MonsterState.PATROL);
        monster.setLastMoveTime(System.currentTimeMillis());

        return NodeStatus.RUNNING;
    }
}
