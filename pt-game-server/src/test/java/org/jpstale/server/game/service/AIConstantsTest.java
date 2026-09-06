package org.jpstale.server.game.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * M5 验收:冻结参数单测。
 * 断言 AIConstants 值与"每 tick 步长 ×20Hz → 64/128 world/s"的换算,防回归改动。
 */
public class AIConstantsTest {

    @Test
    public void speedConstantsMatchDesignD5() {
        // 3.2/6.4 world/tick @20Hz = 64/128 world/s
        assertEquals("walk 应 64 world/s",
            64.0, AIConstants.WALK_STEP_TICK * AIConstants.TICK_RATE_HZ, 1e-9);
        assertEquals("run 应 128 world/s",
            128.0, AIConstants.RUN_STEP_TICK * AIConstants.TICK_RATE_HZ, 1e-9);
    }

    @Test
    public void gateConstantsMatchDecisions() {
        assertEquals("D10 邻近半径 = DISCONNECT 1810", 1810.0f, AIConstants.ACTIVE_RADIUS, 0.0f);
        assertEquals("D10 回收 60s", 60_000L, AIConstants.NO_PLAYER_REMOVE_MS);
        assertEquals("索敌高度差 140", 140.0, AIConstants.SCAN_HEIGHT_DIFF, 1e-9);
        assertEquals("近战高度差 64", 64.0, AIConstants.ATTACK_HEIGHT_DIFF, 1e-9);
        assertEquals("丢目标下限 CONNECT 1086", 1086.0, AIConstants.MIN_LOSE_RANGE, 1e-9);
    }
}
