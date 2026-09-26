package org.jpstale.server.game.skill.job;

import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.skill.JobSkillsCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PikemanSkills} 的**登记表与面板**特征测试（结算路径依赖运行时实体，由真机验收覆盖）。
 *
 * <p>钉三件事：
 * <ol>
 *   <li>**注册即迁移**：`handles` 与注册表同源 —— 已迁 3 招为 true，其余（Ground Pike）为 false；
 *       这也是 `SkillCastService.begin` 迁入门的数据来源；</li>
 *   <li>**面板与结算同一张表**：`powerPct` 直接读生成物参数表（Pike Wind 二维区间 /
 *       Jumping Crash 单值复制），模型不是"攻击力×百分比"的（Critical Hit）报 null —— 不编数；</li>
 *   <li>**目录分派**：`JobSkillsCatalog` 按职业号（skillId 高 16 位）找实现，别家职业查不到。</li>
 * </ol>
 */
class PikemanSkillsTest {

    private static SkillDataRegistry data;
    private static PikemanSkills pikeman;
    private static JobSkillsCatalog catalog;

    @BeforeAll
    static void load() throws ReflectiveOperationException {
        data = new SkillDataRegistry();
        data.load();
        pikeman = new PikemanSkills();
        java.lang.reflect.Field f = PikemanSkills.class.getDeclaredField("skillData");
        f.setAccessible(true);
        f.set(pikeman, data);
        catalog = new JobSkillsCatalog(List.of(pikeman));
    }

    @Test
    void 注册即迁移_未登记的招不算已迁() {
        assertTrue(pikeman.handles(SkillIds.PIKE_WIND.id()));
        assertTrue(pikeman.handles(SkillIds.CRITICAL_HIT.id()));
        assertTrue(pikeman.handles(SkillIds.JUMPING_CRASH.id()));
        assertFalse(pikeman.handles(SkillIds.GROUND_PIKE.id()), "Ground Pike 未迁 ⇒ NOT_MIGRATED 走旧路");
        assertEquals(4, pikeman.job());
    }

    @Test
    void 面板百分比与结算同一张表() {
        assertArrayEquals(new int[]{3, 20}, pikeman.powerPct(SkillIds.PIKE_WIND.id(), 1),
                "1 级 = Pike_Wind_Damage[0] = {3,20}");
        assertArrayEquals(new int[]{21, 80}, pikeman.powerPct(SkillIds.PIKE_WIND.id(), 10),
                "10 级 = Pike_Wind_Damage[9] = {21,80}");
        assertArrayEquals(new int[]{55, 55}, pikeman.powerPct(SkillIds.JUMPING_CRASH.id(), 1),
                "Jumping Crash 单值表 ⇒ min=max=Jumping_Crash_Damage[0]");
        assertNull(pikeman.powerPct(SkillIds.CRITICAL_HIT.id(), 1),
                "Critical Hit 是暴击率模型 ⇒ 不报伤害百分比");
        assertNull(pikeman.powerPct(SkillIds.PIKE_WIND.id(), 0), "未学（point=0）⇒ 没有这个数");
        assertNull(pikeman.powerPct(SkillIds.GROUND_PIKE.id(), 1), "未迁的招 ⇒ 没有面板数据（不编）");
    }

    @Test
    void 目录按职业号分派() {
        assertTrue(catalog.knows(SkillIds.PIKE_WIND.id()), "pikeman 的招查得到实现");
        assertFalse(catalog.knows(SkillIds.GROUND_PIKE.id()), "pikeman 未迁的招");
        assertFalse(catalog.knows(SkillIds.RAVING.id()), "fighter 的招（job 段=1）目录里没有 job 1 的类");
        assertNull(catalog.of(1), "job 1 还没有技能类");
        assertNull(catalog.of(99), "不存在的职业号");
    }
}
