package org.jpstale.server.game.service;

import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 条件+动作节点：巡逻 / 归位
 *
 * 只负责设置怪物状态（PATROL / RETURN）和巡逻目标点。
 * 实际位移由 MovementService 每 tick 执行。
 *
 * 原版逻辑（exm AI.cpp）：
 * - 离出生点超过 moveRange → RETURN 状态，走回出生点
 * - 在活动范围内 → PATROL 状态，随机选一个巡逻点走过去
 * - 到达巡逻点后选新点，重复
 */
public class PatrolNode extends BehaviorNode {

    @Override
    public NodeStatus tick(Monster monster, AiContext context) {
        float sx = monster.getSpawnX();
        float sz = monster.getSpawnZ();
        float range = monster.getMoveRange() > 0 ? monster.getMoveRange() : 50.0f;

        // 归位判定：离出生点超过活动范围
        float homeDx = sx - monster.getX();
        float homeDz = sz - monster.getZ();
        float homeDist = (float) Math.sqrt(homeDx * homeDx + homeDz * homeDz);
        if (homeDist > range) {
            monster.setState(MonsterState.RETURN);
            return NodeStatus.RUNNING;
        }

        // 生成巡逻目标点（没有则随机一个出生点附近的点）
        if (context.getPatrolX() == 0 && context.getPatrolZ() == 0) {
            generatePatrolPoint(context, sx, sz, range);
        }

        // 到达巡逻点判定
        float dx = context.getPatrolX() - monster.getX();
        float dz = context.getPatrolZ() - monster.getZ();
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        if (dist <= 1.0f) {
            // 到达 → 选新巡逻点，返回 SUCCESS 触发重新决策
            generatePatrolPoint(context, sx, sz, range);
            return NodeStatus.SUCCESS;
        }

        // 设置巡逻状态（MovementService 每 tick 据此以 walk 速度移向巡逻点）
        monster.setState(MonsterState.PATROL);
        return NodeStatus.RUNNING;
    }

    private void generatePatrolPoint(AiContext context, float sx, float sz, float range) {
        context.setPatrolX(sx + ThreadLocalRandom.current().nextFloat() * range * 2 - range);
        context.setPatrolZ(sz + ThreadLocalRandom.current().nextFloat() * range * 2 - range);
    }
}
