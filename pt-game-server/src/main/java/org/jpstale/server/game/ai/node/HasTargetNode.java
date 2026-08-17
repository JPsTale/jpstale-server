package org.jpstale.server.game.ai.node;

import org.jpstale.server.game.ai.BehaviorNode;
import org.jpstale.server.game.ai.NodeStatus;
import org.jpstale.server.game.ai.AiContext;
import org.jpstale.server.game.monster.Monster;

/**
 * 条件节点：检查是否有目标
 */
public class HasTargetNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        return monster.getTargetPlayerId() != null ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
    }
}
