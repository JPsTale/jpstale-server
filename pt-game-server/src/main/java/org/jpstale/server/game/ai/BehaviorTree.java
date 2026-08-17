package org.jpstale.server.game.ai;

import org.jpstale.server.game.monster.Monster;

/**
 * 行为树
 */
public class BehaviorTree {

    private final BehaviorNode root;

    public BehaviorTree(BehaviorNode root) {
        this.root = root;
    }

    /**
     * 执行行为树
     */
    public NodeStatus tick(Monster monster, AiContext context) {
        return root.tick(monster, context);
    }
}
