package org.jpstale.common.service.props;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerProperties} 的特征测试（`characterinfo.props` 的解析/序列化 + 键注册表）。
 *
 * <p>重点在**抛**的那几条：值不是 int 时若静默当 0，存档坏了会演成"这个角色没学过这个技能"
 * —— 少给点数、零报错（对照 AGENTS #12）。
 */
class PlayerPropertiesTest {

    @Test
    void 键值包往返() {
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put(PlayerKey.QUEST_LEVEL_55.getKey(), 1);
        in.put(SkillKeys.point(0x040101), 3);
        assertEquals(in, PlayerProperties.parse(PlayerProperties.serialize(in)));

        // 负值也是合法 int（洗点/透支类语义由调用方定义，解析层不该改写数值）
        assertEquals(Map.of("a", -2), PlayerProperties.parse(PlayerProperties.serialize(Map.of("a", -2))));
    }

    @Test
    void 缺值是常态_空与空白都当空包() {
        assertEquals(Map.of(), PlayerProperties.parse(null));
        assertEquals(Map.of(), PlayerProperties.parse(""));
        assertEquals(Map.of(), PlayerProperties.parse("   "));
        assertEquals(Map.of(), PlayerProperties.parse("{}"));
        assertEquals("{}", PlayerProperties.serialize(null));
    }

    @Test
    void 值不是整数就抛且消息带键名() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlayerProperties.parse("{\"quest.level_55\":\"x\"}"));
        assertTrue(e.getMessage().contains("quest.level_55"), e.getMessage());

        // 小数、超 int、null 三种都不是"可当 0 用"的值
        assertThrows(IllegalArgumentException.class, () -> PlayerProperties.parse("{\"a\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProperties.parse("{\"a\":99999999999}"));
        assertThrows(IllegalArgumentException.class, () -> PlayerProperties.parse("{\"a\":null}"));

        // 第二个键坏掉时也要抛（不能"读到哪算哪"地留下半个包）
        assertThrows(IllegalArgumentException.class,
                () -> PlayerProperties.parse("{\"a\":1,\"b\":\"x\"}"));
    }

    @Test
    void 顶层不是对象或不是JSON都抛() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> PlayerProperties.parse("[1,2]"));
        assertTrue(e1.getMessage().contains("JSON 对象"), e1.getMessage());

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> PlayerProperties.parse("{oops"));
        assertTrue(e2.getMessage().contains("不是合法 JSON"), e2.getMessage());
    }

    @Test
    void 默认值取自注册表() {
        for (PlayerKey k : PlayerKey.values()) {
            assertEquals(k.getDefaultValue(), PlayerProperties.defaultOf(k.getKey()), k.name());
        }
        assertEquals(SkillKey.POINT.getDefaultValue(),
                PlayerProperties.defaultOf(SkillKeys.point(0x040101)));
        assertEquals(SkillKey.MASTERY.getDefaultValue(),
                PlayerProperties.defaultOf(SkillKeys.mastery(0x040101)));

        // 未注册 / null ⇒ 0（不是异常：默认值查询是"读侧"，读不到的键返回值本身可由调用方覆盖）
        assertEquals(0, PlayerProperties.defaultOf("没人注册的键"));
        assertEquals(0, PlayerProperties.defaultOf(null));
    }

    @Test
    void 注册表判据() {
        assertTrue(PlayerProperties.isKnown(PlayerKey.QUEST_LEVEL_55.getKey()));
        assertTrue(PlayerProperties.isKnown(SkillKeys.mastery(0x020101)));

        assertFalse(PlayerProperties.isKnown("foo.bar"));
        assertFalse(PlayerProperties.isKnown(null));
        // 技能键只有 point / mastery 两个字段（第三个字段名不许当成已知）
        assertFalse(PlayerProperties.isKnown("skill.0x040101.rating"));
        assertFalse(PlayerProperties.isKnown("skill.0x040101"));
    }
}
