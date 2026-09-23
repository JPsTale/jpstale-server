package org.jpstale.server.game.service;

import org.jpstale.server.game.model.MonsterState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiEngine#summonNoTargetState} 的特征测试 —— 召唤物"跟主人"用哪个状态执行。
 *
 * <p>
 * 这条判据**写错过一次**，所以钉住：最初把跟随接到 `RETURN`（因为召唤物的出生锚就是主人，
 * `RETURN` 的位移目标正好对上），结果 **`RETURN` 的位移是写死的走路** ⇒ 召唤物
 * "打怪会跑、跟你走只会走"（用户 2026-09-23 实测点破）。
 * 原版跟随主人那条是 `if (!SetMotionFromCode(RUN)) SetMotionFromCode(WALK)`
 * ——**能跑就跑**（`character.cpp:5820-5828`）。
 *
 * <p>
 * 反例（若出现则本实现是错的）：把 `summonNoTargetState` 改回恒等（RETURN 保持 RETURN）、
 * 或者改 `MovementService` 的 RETURN 分支让它也会跑 —— 后者会连带改掉**野怪归位**的行为，
 * 那是另一件事（本测试只覆盖召唤物这条链）。
 */
class AiEngineTest {

    @Test
    void 跟随主人不得用RETURN_它是写死走路的() {
        assertEquals(MonsterState.CHASE, AiEngine.summonNoTargetState(MonsterState.RETURN),
            "超出 leash 要'跟上主人'：RETURN 写死走路（MONSTER_WALK_STEP / 动画 token 0x0050），"
                + "原版跟随是 RUN 优先 —— 必须换成 CHASE");
        assertNotEquals(MonsterState.RETURN, AiEngine.summonNoTargetState(MonsterState.RETURN),
            "★ 这条就是错的现场：一旦这里又是 RETURN，召唤物跟人只会走路");
    }

    @Test
    void 其余状态原样透传() {
        assertEquals(MonsterState.IDLE, AiEngine.summonNoTargetState(MonsterState.IDLE),
            "在 leash 内就站着 —— 不该被改成会动的状态");
        assertEquals(MonsterState.CHASE, AiEngine.summonNoTargetState(MonsterState.CHASE));
        assertEquals(MonsterState.ATTACK, AiEngine.summonNoTargetState(MonsterState.ATTACK));
        assertEquals(MonsterState.DEAD, AiEngine.summonNoTargetState(MonsterState.DEAD));
    }

    @Test
    void 能跑就跑的依据_步长与动画token同源() {
        // 这两条是"CHASE = 能跑就跑"的实现凭据（`MovementService` / `MonsterAOI.animOf`）：
        //   · 步长：canRun ? MONSTER_RUN_STEP : MONSTER_WALK_STEP
        //   · 动画 token：canRun ? 0x0060(RUN) : 0x0050(WALK)
        // 它们读的**同一个**判据 `Monster.isCanRun()`（IQ≥6 且该模型有 RUN 动画），
        // 所以"速度"与"客户端看到的动作"不会各说一套。
        // 这里只断言常量之间的关系 —— 跑必须比走快，否则"能跑就跑"没有意义。
        assertTrue(org.jpstale.server.common.codec.GameConstants.MONSTER_RUN_STEP
                > org.jpstale.server.common.codec.GameConstants.MONSTER_WALK_STEP,
            "RUN 步进必须大于 WALK 步进");
    }
}
