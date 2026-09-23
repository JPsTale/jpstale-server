package org.jpstale.common.service.props;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerKey} 的**不变量**测试：`getKey()` 必须等于"模板 + 实参"拼出来的那个存储键。
 *
 * <p>为什么值得单测：显式成员（`QUEST_LEVEL_*`）的键是**手写**的，而读侧按名字取
 * （`player.getPropInt(PlayerKey.QUEST_LEVEL_55.getKey())`）。两边打错字时不会报错，
 * 只会读到"一个不存在的键"⇒ 默认值 0 ⇒ 技能点少给。
 */
class PlayerKeyTest {

    /** 5 个显式成员（模板成员 `QUEST_ID` 不在其中）。 */
    private static final List<PlayerKey> EXPLICIT = List.of(
            PlayerKey.QUEST_LEVEL_55, PlayerKey.QUEST_LEVEL_70, PlayerKey.QUEST_LEVEL_80,
            PlayerKey.QUEST_LEVEL_80_2, PlayerKey.QUEST_LEVEL_90_2);

    @Test
    void 显式成员的键等于模板拼接结果() {
        assertEquals(PlayerKey.QUEST_ID.format("level_55"), PlayerKey.QUEST_LEVEL_55.getKey());
        assertEquals(PlayerKey.QUEST_ID.format("level_70"), PlayerKey.QUEST_LEVEL_70.getKey());
        assertEquals(PlayerKey.QUEST_ID.format("level_80"), PlayerKey.QUEST_LEVEL_80.getKey());
        assertEquals(PlayerKey.QUEST_ID.format("level_80_2"), PlayerKey.QUEST_LEVEL_80_2.getKey());
        assertEquals(PlayerKey.QUEST_ID.format("level_90_2"), PlayerKey.QUEST_LEVEL_90_2.getKey());
    }

    @Test
    void 反例守卫_显式成员不许退化成id片段() {
        for (PlayerKey k : EXPLICIT) {
            assertTrue(k.getKey().startsWith("quest."),
                    k.name() + ".getKey() 必须是完整存储键（写成 level_55 这类片段会静默读到 0）：" + k.getKey());
        }
    }

    @Test
    void 五个显式成员的键互不相同() {
        assertEquals(EXPLICIT.size(), EXPLICIT.stream().map(PlayerKey::getKey).distinct().count());
    }
}
