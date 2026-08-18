package org.jpstale.server.game.service;

import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;

/**
 * 条件节点：检查怪物是否死亡
 */
public class IsDeadNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        return monster.isAlive() ? NodeStatus.FAILURE : NodeStatus.SUCCESS;
    }
}
