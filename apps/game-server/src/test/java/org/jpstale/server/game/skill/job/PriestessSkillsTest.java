package org.jpstale.server.game.skill.job;

import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.skill.JobSkillsCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PriestessSkills} 的**登记表 / 面板 / 选敌算法 / 减益状态**特征测试
 * （结算与治疗路径依赖运行时实体与会话，由真机验收覆盖）。
 *
 * <p>钉五件事：
 * <ol>
 *   <li>**注册即迁移**：已迁 6 招为 true，未迁的（Grand Healing / Summon Muspell）为 false；</li>
 *   <li>**目录分派**：两个职业类共存时按 job 段各归各家；</li>
 *   <li>**面板与结算同一张表**：Holy Bolt / Multi Spark 报表值，其余模型报 null；</li>
 *   <li>**Holy Mind 减益**：生效期内返回表值，过期（或未施放）返回 0 —— AiEngine 的出手削伤靠它；</li>
 *   <li>**选敌算法逐条对齐规格书**：链式 = 最近邻 + 排除已选 + 跳跃范围截断（§2.3），
 *       轮转 = 从滚动位继续 + 3D 距离 + |dy| 过滤（§3.3），且两者都吃"存活/非召唤/同图"前置。</li>
 * </ol>
 */
class PriestessSkillsTest {

    private static SkillDataRegistry data;
    private static PriestessSkills priestess;
    private static JobSkillsCatalog catalog;

    /** 造一只摆在坐标上的怪（召唤物/异图位不在选敌测试里需要）。 */
    private static Monster at(long id, double x, double y, double z) {
        Monster m = new Monster(id);
        m.setX(x);
        m.setY(y);
        m.setZ(z);
        return m;
    }

    @BeforeAll
    static void load() throws ReflectiveOperationException {
        data = new SkillDataRegistry();
        data.load();
        priestess = new PriestessSkills();
        java.lang.reflect.Field f = PriestessSkills.class.getDeclaredField("skillData");
        f.setAccessible(true);
        f.set(priestess, data);
        PikemanSkills pikeman = new PikemanSkills();
        catalog = new JobSkillsCatalog(List.of(pikeman, priestess));
    }

    @Test
    void 注册即迁移_未登记的招不算已迁() {
        assertEquals(8, priestess.job());
        for (SkillIds s : new SkillIds[]{SkillIds.HEALING, SkillIds.HOLY_BOLT, SkillIds.MULTISPARK,
                SkillIds.HOLY_MIND, SkillIds.DIVINE_LIGHTNING, SkillIds.CHAIN_LIGHTNING}) {
            assertTrue(priestess.handles(s.id()), s + " 应已迁入");
        }
        assertFalse(priestess.handles(SkillIds.GRAND_HEALING.id()), "Grand Healing 本轮未迁（语义未取全）");
        assertFalse(priestess.handles(SkillIds.SUMMON_MUSPELL.id()), "召唤未迁");
    }

    @Test
    void 目录按职业号分派_两个职业共存() {
        assertTrue(catalog.knows(SkillIds.HEALING.id()), "祭司的招");
        assertTrue(catalog.knows(SkillIds.PIKE_WIND.id()), "枪手的招");
        assertFalse(catalog.knows(SkillIds.GROUND_PIKE.id()), "枪手未迁的招");
        assertEquals(priestess, catalog.of(8));
        assertNull(catalog.of(99));
    }

    @Test
    void 面板百分比_只有乘算模型才报() {
        assertArrayEquals(new int[]{14, 14}, priestess.powerPct(SkillIds.HOLY_BOLT.id(), 1),
                "HolyBolt_Damage[0] = 14");
        assertArrayEquals(new int[]{16, 16}, priestess.powerPct(SkillIds.MULTISPARK.id(), 1),
                "Multi Spark 面板显示基数 M_Spark_Damage[0] = 16（实际倍率随火花数上浮）");
        assertNull(priestess.powerPct(SkillIds.HEALING.id(), 1), "回复模型不报伤害百分比");
        assertNull(priestess.powerPct(SkillIds.HOLY_MIND.id(), 1), "减益模型不报伤害百分比");
        assertNull(priestess.powerPct(SkillIds.DIVINE_LIGHTNING.id(), 1), "裸伤替换模型不报百分比");
        assertNull(priestess.powerPct(SkillIds.HOLY_BOLT.id(), 0), "未学 ⇒ 没有这个数");
    }

    @Test
    void holyMind减益_生效期内有值_过期归零() {
        Monster m = new Monster(1L);
        assertEquals(0, m.holyMindDecPct(), "没施过 = 0");

        m.applyHolyMind(22, -1);            // 负时长 ⇒ 立即过期
        assertEquals(0, m.holyMindDecPct(), "过期的减益 = 0");

        m.applyHolyMind(22, 60_000);        // 生效期内
        assertEquals(22, m.holyMindDecPct());
    }

    /* ────────────── 选敌算法（规格书 §2.3 / §3.3 的可执行反例） ────────────── */

    /** 施法者摆在原点（构造是纯赋值，测试只读坐标，player/session 留空）。 */
    private static PlayerEntity casterAtOrigin() {
        return new PlayerEntity(900L, 1L, null, null);
    }

    @Test
    void 最近邻链_从主目标起逐段跳向最近者() {
        // 施法者在原点；主目标 (10,0,0)；候选：(9,0,0) 距主目标 1、(12,0,0) 距 2、(100,0,0) 超范围
        Monster first = at(1, 10, 0, 0);
        Monster near = at(2, 9, 0, 0);
        Monster mid = at(3, 12, 0, 0);
        Monster far = at(4, 100, 0, 0);
        List<Monster> candidates = new ArrayList<>(List.of(mid, far, near, first));

        List<Monster> picked = PriestessSkills.chainNearest(candidates, first, 3, 140f);
        assertEquals(3, picked.size());
        assertSame(first, picked.get(0), "第 0 个 = 主目标");
        assertSame(near, picked.get(1), "跳向距主目标最近者");
        assertSame(mid, picked.get(2), "再跳向距上一个最近者");
        assertFalse(picked.contains(far), "超出跳跃范围的不选");
    }

    @Test
    void 最近邻链_已选过的不回头_高度差70截断() {
        // 两只怪距主目标同为 5：一只在头顶 100（超过 |dy|<70）、一只在旁边 —— 只能选旁边的
        Monster first = at(1, 0, 0, 0);
        Monster high = at(2, 5, 100, 0);
        Monster aside = at(3, 0, 0, 5);
        List<Monster> picked = PriestessSkills.chainNearest(new ArrayList<>(List.of(first, high, aside)),
                first, 3, 140f);
        assertEquals(2, picked.size(), "头顶那只被高度差挡掉");
        assertSame(aside, picked.get(1));
    }

    @Test
    void 轮转扫描_从滚动位继续_数量截断() {
        // 5 只等距排开（都在 3D 180 内、|dy|=0）：maxTargets=2 时，
        // 从 offset 0 起选前 2 只（id 序），从 offset 2 起选后 2 只 —— 两次施放选中集合不同（规格书 R1）。
        List<Monster> candidates = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            candidates.add(at(id, id * 10, 0, 0));
        }
        PlayerEntity self = casterAtOrigin();

        List<Monster> first = PriestessSkills.scanRoundRobin(candidates, 0, 2, self, 180.0, 65);
        assertEquals(2, first.size());
        assertSame(candidates.get(0), first.get(0));
        assertSame(candidates.get(1), first.get(1));

        List<Monster> second = PriestessSkills.scanRoundRobin(candidates, 2, 2, self, 180.0, 65);
        assertSame(candidates.get(2), second.get(0), "从上次结束处继续扫");
        assertSame(candidates.get(3), second.get(1));
    }

    @Test
    void 轮转扫描_范围与高度差过滤() {
        // (200,0,0) 超 3D 180；(30, 70, 0) |dy|=70 不 < 65；(30,10,0) 合格
        List<Monster> candidates = new ArrayList<>(List.of(
                at(1, 200, 0, 0), at(2, 30, 70, 0), at(3, 30, 10, 0)));
        List<Monster> picked = PriestessSkills.scanRoundRobin(candidates, 0, 5, casterAtOrigin(), 180.0, 65);
        assertEquals(1, picked.size());
        assertSame(candidates.get(2), picked.get(0));
    }
}
