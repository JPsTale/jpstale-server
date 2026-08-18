package org.jpstale.server.game.model;

import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 顺序节点
 * 按顺序执行子节点，遇到失败则返回失败
 */
public class SequenceNode extends BehaviorNode {

    private final List<BehaviorNode> children = new ArrayList<>();

    public SequenceNode(BehaviorNode... children) {
        this.children.addAll(Arrays.asList(children));
    }

    public SequenceNode(List<BehaviorNode> children) {
        this.children.addAll(children);
    }

    public void addChild(BehaviorNode child) {
        children.add(child);
    }

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        for (BehaviorNode child : children) {
            NodeStatus status = child.tick(monster, context);
            if (status != NodeStatus.SUCCESS) {
                return status;
            }
        }
        return NodeStatus.SUCCESS;
    }
}
