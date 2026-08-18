package org.jpstale.server.game.service;

import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;

/**
 * 条件节点：检查是否有目标
 */
public class HasTargetNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        return monster.getTargetPlayerId() != null ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
    }
}
