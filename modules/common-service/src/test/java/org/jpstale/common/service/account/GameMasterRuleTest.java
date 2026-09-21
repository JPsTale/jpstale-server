package org.jpstale.common.service.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GM 判据的回归。
 *
 * 背景：收编前仓库里有两份判据不一致的 {@code isGm}（一处用「与」、一处用「或」），
 * 而 Web 管理端的权限门也依赖这条判定 —— 判据漂移会**静默**地把人放进来或挡在外面。
 * 这里把「与」这个选择钉住，尤其是两个 {@code 单边非零} 的情形（它们正是分歧点）。
 */
class GameMasterRuleTest {

    @Test
    void 两列都非零才算是GM() {
        // 活库实测的唯一样本
        assertTrue(GameMasterRule.isGameMaster(1, 4));
    }

    @Test
    void 单边非零不算GM_这两个断言就是与或的分歧点() {
        assertFalse(GameMasterRule.isGameMaster(1, 0), "type 非零但 level 为 0");
        assertFalse(GameMasterRule.isGameMaster(0, 4), "level 大于 0 但 type 为 0");
    }

    @Test
    void 全零不算GM() {
        assertFalse(GameMasterRule.isGameMaster(0, 0));
    }

    @Test
    void level为0或负数都不算GM_failClosed的是这一半() {
        assertFalse(GameMasterRule.isGameMaster(1, 0), "level 为 0");
        assertFalse(GameMasterRule.isGameMaster(1, -1), "level 为负数");
    }

    @Test
    void 任一列为null一律不算GM() {
        assertFalse(GameMasterRule.isGameMaster(null, 4), "type 为 null");
        assertFalse(GameMasterRule.isGameMaster(1, null), "level 为 null");
        assertFalse(GameMasterRule.isGameMaster(null, null), "两列都为 null");
    }
}
