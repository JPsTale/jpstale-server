package org.jpstale.server.game.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void d7RedlineMonsterWalkMustNotCatchPlayerRun() {
        // D7 红线:怪走速必须 < 玩家 Move_Speed=1 跑速(以 N1 ≈ 105.2 world/s 为基准),
        // 否则新手跑不掉。当前怪走 64 < 105.2 ✓;1 档走(≈41)被追上属正常(靠跑逃生)。
        double monsterWalk = AIConstants.WALK_STEP_TICK * AIConstants.TICK_RATE_HZ; // 64 world/s
        double monsterRun = AIConstants.RUN_STEP_TICK * AIConstants.TICK_RATE_HZ;    // 128 world/s
        double n1PlayerRun = 105.2;

        assertTrue("D7 红线:怪走速必须 < 玩家跑速(当前 " + monsterWalk + " >= " + n1PlayerRun + ")",
            monsterWalk < n1PlayerRun);
        // 次要:跑档必须显著快于走档(1:2),且不得出现 1/5 速 bug(≈12.8)
        assertTrue("怪跑 应快于怪走", monsterRun > monsterWalk);
        assertTrue("怪走 不得退化至 1/5 速 bug",
            Math.abs(monsterWalk - 12.8) > 1e-9);
    }
}
