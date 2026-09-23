package org.jpstale.common.service.props;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillKeys} 的特征测试：键形 + **传参防线**。
 *
 * <p>`IPlayerKey.format(Object...)` 是弱类型，所以"坏 id"只能靠白名单挡住 ——
 * 挡不住的话键会静默拼错，读侧拿到默认值 0（点数白给、等级不涨、零报错）。
 */
class SkillKeysTest {

    @Test
    void point与mastery是两个字段() {
        assertEquals("skill.0x040401.point", SkillKeys.point(0x040401));
        assertEquals("skill.0x040401.mastery", SkillKeys.mastery(0x040401));
    }

    @Test
    void 键里的hex一律小写六位() {
        // 补零到 6 位（不然 0x040101 与 0x40101 会拼成两个键）
        assertEquals("0x040101", SkillKeys.hex(0x040101));
        // ⚠ 生成物 `skillIdHex` 那列是大写（0x0B0101），键里必须是小写 —— 这是本条存在的理由
        assertEquals("0x0b0101", SkillKeys.hex(0x0B0101));
        assertEquals("skill.0x0a0101.point", SkillKeys.point(0x0A0101), "shaman 一转一槽");
    }

    @Test
    void 日志用的describe不校验但同形() {
        assertEquals("0x040101", SkillKeys.describe(0x040101));
        assertEquals("0x0b0101", SkillKeys.describe(0x0B0101));
        // 不校验：垃圾整数也要能打出来（正是要靠它定位"客户端发了什么"）
        assertEquals("0x75bcd15", SkillKeys.describe(123456789));
        assertEquals("0", SkillKeys.describe(0));
        assertEquals("-5", SkillKeys.describe(-5));
    }

    @Test
    void 职业号到目录名() {
        assertEquals("pikeman", SkillKeys.classDirOfJob(4));
        assertEquals("martial", SkillKeys.classDirOfJob(11), "第 11 职业的名字与源码组名不同");
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.classDirOfJob(0));
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.classDirOfJob(12));
    }

    @Test
    void 不在表里的id要抛() {
        // 0 / 负数：不是任何技能（0 还兼作"无前置"的哨兵值，绝不能拼出键）
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.point(0));
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.mastery(-1));

        // 职业段超范围（job 12）与档内槽超范围（槽 5）都不是合法组合
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.point(0x0C0101));
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.point(0x010105));
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.point(0x010600));
        // 槽序只有 4：`0x040521` 的 slotInTier = 0x21 = 33
        assertThrows(IllegalArgumentException.class, () -> SkillKeys.mastery(0x040521));

        // 消息里要有那个 id（否则"哪个请求坏了"查不到）
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SkillKeys.point(0x040521));
        assertTrue(e.getMessage().contains("0x040521"), e.getMessage());
    }

    @Test
    void 键形与生成物的220个id一一对上() {
        // 220 = 11 职业 × 5 档 × 4 槽：每个组合都必须能拼出键（且唯一）
        Set<String> keys = new HashSet<>();
        for (int job = 1; job <= 11; job++) {
            for (int tier = 1; tier <= 5; tier++) {
                for (int slot = 1; slot <= 4; slot++) {
                    int id = (job << 16) | (tier << 8) | slot;
                    assertTrue(keys.add(SkillKeys.point(id)), "键重复：" + SkillKeys.describe(id));
                }
            }
        }
        assertEquals(220, keys.size());
    }
}
