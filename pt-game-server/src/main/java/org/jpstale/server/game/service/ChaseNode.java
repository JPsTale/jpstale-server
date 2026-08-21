package org.jpstale.server.game.service;

import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 条件+动作节点：追击目标
 *
 * 只负责设置怪物状态为 CHASE 并刷新目标位置。
 * 实际位移由 MovementService 每 tick 执行。
 */
public class ChaseNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        PlayerSession target = context.getTargetPlayer();
        if (target == null) {
            return NodeStatus.FAILURE;
        }

        // 刷新目标位置（玩家可能在移动）
        context.setTargetX(target.getX());
        context.setTargetZ(target.getZ());

        // 设置追击状态（MovementService 每 tick 据此以 run 速度移向目标）
        monster.setState(MonsterState.CHASE);

        // 到达判定：距离 ≤ 0.5 时视为进入攻击范围（由 InAttackRangeNode 处理）
        float dx = target.getX() - monster.getX();
        float dz = target.getZ() - monster.getZ();
        float distSq = dx * dx + dz * dz;
        if (distSq <= 0.5f * 0.5f) {
            return NodeStatus.SUCCESS;
        }

        return NodeStatus.RUNNING;
    }
}
