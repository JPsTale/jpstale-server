package org.jpstale.server.game.service;

import org.jpstale.server.game.model.MonsterState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * M5 验收①:状态机表驱动单测。
 *
 * 语义对齐 docs/monster-ai-entity-design.md §2.1 决策图:
 *   有可锁目标 → 攻击距内 ATTACK / 超出 CHASE;
 *   无目标     → 距出生锚 > leash RETURN / 否则 IDLE。
 * 断言面是 AiEngine.decide(纯函数,无副作用);运行时由 update() 用同一决策驱动副作用。
 */
public class AiStateMachineTest {

    @Test
    public void transitionTableMatchesDesignStateMachine() {
        //   hasValidTarget | inAttackRange | homeDist vs leash        → expected
        assertDecision( true,  true,  0.0,  _leash(1000), MonsterState.ATTACK);
        assertDecision( true,  false, 0.0,  _leash(1000), MonsterState.CHASE);
        assertDecision( false, false, 2000, _leash(1000), MonsterState.RETURN);
        assertDecision( false, false, 500,  _leash(1000), MonsterState.IDLE);
    }

    @Test
    public void leashBoundaryHomeDistAtLeashIsIdle() {
        // 边界:恰好等于 leash 不越界 → IDLE(先择站立再判断超界,不抖动)
        assertDecision(false, false, 1000, _leash(1000), MonsterState.IDLE);
    }

    @Test
    public void attackRangeWinsRegardlessOfHomeDistance() {
        // 目标在攻击距内 → ATTACK,与归位距离无关(目标优先级最高)
        assertDecision(true, true, 9999, _leash(1000), MonsterState.ATTACK);
    }

    private static void assertDecision(boolean hasTarget, boolean inAttackRange,
                                       double homeDist, double leash, MonsterState expected) {
        assertEquals("decide(" + hasTarget + "," + inAttackRange + ",dist=" + homeDist
                + ",leash=" + leash + ") 应 → " + expected,
            expected, AiEngine.decide(hasTarget, inAttackRange, homeDist, leash));
    }

    private static double _leash(double v) {
        return v;
    }
}