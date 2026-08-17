package org.jpstale.server.game.ai.node;

import org.jpstale.server.game.ai.BehaviorNode;
import org.jpstale.server.game.ai.NodeStatus;
import org.jpstale.server.game.ai.AiContext;
import org.jpstale.server.game.monster.Monster;
import org.jpstale.server.game.monster.MonsterState;

/**
 * 条件节点：检查怪物是否死亡
 */
public class IsDeadNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        return monster.isAlive() ? NodeStatus.FAILURE : NodeStatus.SUCCESS;
    }
}
