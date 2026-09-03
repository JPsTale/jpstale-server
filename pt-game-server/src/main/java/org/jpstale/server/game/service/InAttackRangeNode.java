package org.jpstale.server.game.service;

import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 条件节点：检查是否在攻击范围内
 */
public class InAttackRangeNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        PlayerSession target = context.getTargetPlayer();
        if (target == null) {
            return NodeStatus.FAILURE;
        }

        // TODO: 获取玩家实际位置
        double targetX = context.getTargetX();
        double targetZ = context.getTargetZ();
        double distance = monster.distanceTo(targetX, 0, targetZ);

        return distance <= monster.getAttackRange() ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
    }
}
