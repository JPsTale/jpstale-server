package org.jpstale.server.game.ai;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.ai.node.*;
import org.jpstale.server.game.monster.Monster;
import org.jpstale.server.game.world.AOIManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 引擎
 * 管理怪物的 AI 行为
 */
@Slf4j
@Component
public class AiEngine {

    @Autowired
    private AOIManager aoiManager;

    private final Map<Long, AiContext> monsterContexts = new ConcurrentHashMap<>();
    private final Map<Integer, BehaviorTree> behaviorTrees = new ConcurrentHashMap<>();

    public void init() {
        // 为每种 AI 类型构建行为树
        behaviorTrees.put(0, buildDefaultTree()); // 默认：巡逻
        behaviorTrees.put(1, buildAggressiveTree()); // 攻击型
        behaviorTrees.put(2, buildDefensiveTree()); // 防御型
    }

    /**
     * 更新怪物 AI
     */
    public void update(Monster monster) {
        if (!monster.isAlive()) {
            return;
        }

        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        
        // 检查决策间隔
        if (!context.shouldDecide()) {
            return;
        }

        // 获取行为树
        BehaviorTree tree = behaviorTrees.get(0); // 默认使用攻击型
        if (tree != null) {
            tree.tick(monster, context);
            context.updateDecisionTime();
        }
    }

    /**
     * 设置怪物目标
     */
    public void setTarget(Monster monster, Long playerId, float targetX, float targetZ) {
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        // TODO: 从 SessionManager 获取 PlayerSession
        context.setTargetX(targetX);
        context.setTargetZ(targetZ);
        monster.setTargetPlayerId(playerId);
    }

    /**
     * 清除怪物目标
     */
    public void clearTarget(Monster monster) {
        AiContext context = monsterContexts.get(monster.getId());
        if (context != null) {
            context.setTargetPlayer(null);
        }
        monster.setTargetPlayerId(null);
    }

    /**
     * 移除怪物上下文
     */
    public void removeContext(long monsterId) {
        monsterContexts.remove(monsterId);
    }

    private BehaviorTree buildDefaultTree() {
        return new BehaviorTree(
            new SelectorNode(
                // 如果死了 → 失败
                new SequenceNode(
                    new IsDeadNode(),
                    new DeadActionNode()
                ),
                // 如果有目标 → 追击/攻击
                new SequenceNode(
                    new HasTargetNode(),
                    new SelectorNode(
                        new SequenceNode(
                            new InAttackRangeNode(),
                            new AttackNode()
                        ),
                        new ChaseNode()
                    )
                ),
                // 否则 → 巡逻
                new PatrolNode()
            )
        );
    }

    private BehaviorTree buildAggressiveTree() {
        return buildDefaultTree(); // 与默认相同
    }

    private BehaviorTree buildDefensiveTree() {
        return buildDefaultTree(); // 与默认相同
    }

    /**
     * 动作节点：死亡处理
     */
    private static class DeadActionNode extends BehaviorNode {
        @Override
        public NodeStatus tick(Monster monster, AiContext context) {
            // 怪物死亡，什么都不做
            return NodeStatus.SUCCESS;
        }
    }
}
