package org.jpstale.common.service.skill;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillRules} 的门槛矩阵与钱表（**不连库、不碰 props 之外的任何状态**）。
 *
 * <p>数据用**真**的 {@link SkillDataRegistry}（classpath 上有生成物）：需求等级、槽位、上一槽的 id
 * 全从数据取，不手打 —— 手打的话"断言里的 id 与数据里的 id 各错一半"也能过。
 */
class SkillRulesTest {

    private static SkillDataRegistry data;

    @BeforeAll
    static void load() {
        data = new SkillDataRegistry();
        data.load();
    }

    /** pikeman（job 4）第 index 槽（0 基，按槽序）的数字 id。 */
    private static int idAt(int index) {
        return data.bySlot(4, index).skillId();
    }

    /** 第 index 槽的判定（`freePoints` 直接给，绕开点池的等级门，好让每道门单独受测）。 */
    private static SkillRules.Learn resolveAt(Player p, int index) {
        return SkillRules.resolve(p, data, idAt(index));
    }

    private static Player pikeman(int level, int rank, long gold) {
        Player p = new Player(0);
        p.setJob(4);
        p.setLevel(level);
        p.setRank(rank);
        p.setGold((int) gold);
        return p;
    }

    /** 把 pikeman 的 0..upto-1 槽都设成已学 1 级（只有"槽位开放"这一门要看它）。 */
    private static void chainLearned(Player p, int upto) {
        for (int i = 0; i < upto; i++) {
            p.setPropInt(SkillKeys.point(idAt(i)), 1);
        }
    }

    /* ─────────────── 四道门 + 两条成本：每道门一个用例 ─────────────── */

    @Test
    void 槽位未开放被拒_按转职档() {
        // 1 转只开 0..4：把前 4 槽学会后，第 5 槽（0 基 5）唯一被拒的原因是"槽没开"
        Player p = pikeman(30, 0, 999_999);
        chainLearned(p, 4);
        assertEquals(SkillRules.Reason.OK, SkillRules.judge(p, resolveAt(p, 4), 5), "槽 4 是 1 转的最后一槽");
        assertEquals(SkillRules.Reason.SLOT_LOCKED, SkillRules.judge(p, resolveAt(p, 5), 5));

        // 2 转开 0..12
        Player p2 = pikeman(60, 2, 999_999);
        chainLearned(p2, 12);
        assertEquals(SkillRules.Reason.OK, SkillRules.judge(p2, resolveAt(p2, 12), 5));
        assertEquals(SkillRules.Reason.SLOT_LOCKED, SkillRules.judge(p2, resolveAt(p2, 13), 5));
    }

    @Test
    void 五转四格一律未开放_哪怕满级满转() {
        // 5 转（槽序 16..19）：原版没有 5 转 ⇒ 既无点池也无价目表；`openSlots(4)` 是 17
        // （会放过槽 16），所以这一条必须由"没有价"来挡 —— 挡不住就会算出 0 元技能
        Player p = pikeman(99, 4, 999_999);
        chainLearned(p, 16);
        for (int i = 16; i < 20; i++) {
            SkillRules.Learn learn = resolveAt(p, i);
            assertEquals(SkillRules.Reason.SLOT_LOCKED, learn.reason(),
                    "槽 " + i + "（五转）应被拒，实得 " + learn.reason());
            assertEquals(SkillRules.Reason.SLOT_LOCKED, SkillRules.judge(p, learn, 99));
        }
        assertTrue(data.bySlot(4, 16).tier() == 5, "槽 16 确实是第 5 档");
    }

    @Test
    void 前置槽未学被拒() {
        Player p = pikeman(30, 3, 999_999);
        assertEquals(0, resolveAt(p, 0).prevSkillId(), "第 0 槽没有前置（0 不是合法 id）");
        assertEquals(idAt(0), resolveAt(p, 1).prevSkillId());
        assertFalse(resolveAt(p, 0).hasPrev());

        assertEquals(SkillRules.Reason.PREV_NOT_LEARNED, SkillRules.judge(p, resolveAt(p, 1), 5));

        p.setPropInt(SkillKeys.point(idAt(0)), 1);
        assertEquals(SkillRules.Reason.OK, SkillRules.judge(p, resolveAt(p, 1), 5));
    }

    @Test
    void 等级不够被拒_需求等级加当前等级乘二() {
        // 第 0 槽需求 10 级：1 级时 10+2 > 等级 ⇒ 拒绝
        Player p = pikeman(1, 0, 999_999);
        assertEquals(SkillRules.Reason.LEVEL_TOO_LOW, SkillRules.judge(p, resolveAt(p, 0), 5));

        // 需求等级 + 当前等级×2 ≤ 等级：10 级时只能学第 1 级（10+0），第 2 级要 12
        Player at10 = pikeman(10, 0, 999_999);
        assertEquals(SkillRules.Reason.OK, SkillRules.judge(at10, resolveAt(at10, 0), 5));
        at10.setPropInt(SkillKeys.point(idAt(0)), 1);
        assertEquals(SkillRules.Reason.LEVEL_TOO_LOW, SkillRules.judge(at10, resolveAt(at10, 0), 5),
            "升第 2 级要 10 + 1×2 = 12 级");
    }

    @Test
    void 已满十级被拒() {
        int req = data.bySlot(4, 0).reqLv();
        Player p = pikeman(req + 20, 0, 999_999);   // 高到"等级"这一门一定过
        p.setPropInt(SkillKeys.point(idAt(0)), SkillRules.MAX_POINT);
        SkillRules.Learn learn = resolveAt(p, 0);
        assertEquals(11, learn.newPoint(), "解析出来的目标等级");
        assertEquals(SkillRules.Reason.MAX_POINT, SkillRules.judge(p, learn, 5));
    }

    @Test
    void 技能点不足被拒() {
        Player p = pikeman(30, 0, 999_999);
        assertEquals(SkillRules.Reason.NO_SKILL_POINT, SkillRules.judge(p, resolveAt(p, 0), 0));
        assertEquals(SkillRules.Reason.OK, SkillRules.judge(p, resolveAt(p, 0), 1));
    }

    @Test
    void 钱不够被拒() {
        Player p = pikeman(30, 0, 499);   // 第 1 级的价就是 500
        SkillRules.Learn learn = resolveAt(p, 0);
        assertEquals(500, learn.cost(), "第 1 级只收基数价");
        assertEquals(SkillRules.Reason.NO_GOLD, SkillRules.judge(p, learn, 5));

        p.setGold(500);
        assertEquals(SkillRules.Reason.OK, SkillRules.judge(p, resolveAt(p, 0), 5), "相等就够（不是 >）");
    }

    /* ─────────────── 职业门 / 未知标识 ─────────────── */

    @Test
    void 技能不属于该职业被拒() {
        Player p = pikeman(30, 3, 999_999);
        SkillRules.Learn learn = SkillRules.resolve(p, data, SkillIds.RAVING.id());   // fighter 的
        assertEquals(SkillRules.Reason.WRONG_JOB, learn.reason());
        assertEquals(SkillRules.Reason.WRONG_JOB, SkillRules.judge(p, learn, 5));
        assertEquals("RAVING", learn.constName(), "被拒时也要能报出是哪个技能（日志可读）");
    }

    @Test
    void 不认识的id被拒_不是抛() {
        Player p = pikeman(30, 3, 999_999);
        // 槽 33（slotInTier 只有 1..4）：结构上就不是技能
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, SkillRules.resolve(p, data, 0x040521).reason());
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, SkillRules.resolve(p, data, 0).reason());
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, SkillRules.resolve(p, data, -1).reason());
        // ⚠ id 里的职业段不参与判据：职业门看的是**角色**的 job（这里是 pikeman）
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, SkillRules.resolve(p, data, 0x7F0101).reason());
    }

    @Test
    void 格斗家现在也有技能树() {
        // 生成物给了格斗家 20 行（60 个无源码技能之一）⇒ 不再按"没有技能树"拒
        Player p = new Player(0);
        p.setJob(11);
        p.setLevel(99);
        p.setRank(4);
        SkillRules.Learn learn = SkillRules.resolve(p, data, SkillIds.LOWKICK.id());
        assertTrue(learn.ok(), "格斗家一转一槽应能解析：" + learn.reason());
        assertEquals("LOWKICK", learn.constName());
        assertEquals(0, learn.slotInJob());
    }

    @Test
    void 职业号不在1到11时按没有技能树处理() {
        for (int job : new int[]{0, 12, 99}) {
            Player p = new Player(0);
            p.setJob(job);
            p.setLevel(99);
            assertEquals(SkillRules.Reason.NO_SKILL_TREE,
                    SkillRules.resolve(p, data, SkillIds.PIKE_WIND.id()).reason(), "job " + job);
        }
    }

    /* ─────────────── 钱表与编号 ─────────────── */

    @Test
    void 钱表是基数加每级增量() {
        assertEquals(500, SkillRules.cost(1, 0), "第 1 槽第 1 级");
        assertEquals(1000, SkillRules.cost(1, 1), "500 + 500×1");
        assertEquals(500 + 500 * 9, SkillRules.cost(1, 9), "能实际走到的最高档是升级前的 9");
        assertEquals(40000, SkillRules.cost(16, 0), "第 16 槽基数");
        assertEquals(40000 + 8000L * 9, SkillRules.cost(16, 9));
        // 16 项都在、越界直接抛（表只有 16 项）
        for (int n = 1; n <= 16; n++) {
            assertTrue(SkillRules.cost(n, 0) > 0, "技能编号 " + n);
            assertTrue(SkillRules.hasPrice(n));
        }
        assertThrows(IllegalArgumentException.class, () -> SkillRules.cost(17, 0));
        assertThrows(IllegalArgumentException.class, () -> SkillRules.cost(0, 0));
        // 5 转的编号（17..20）没有价：这是"未开放"的判据，不是"0 元"
        assertFalse(SkillRules.hasPrice(17));
        assertFalse(SkillRules.hasPrice(20));
        assertFalse(SkillRules.hasPrice(0));
    }

    @Test
    void 槽位开放表() {
        assertEquals(5, SkillRules.openSlots(0));
        assertEquals(9, SkillRules.openSlots(1));
        assertEquals(13, SkillRules.openSlots(2));
        assertEquals(17, SkillRules.openSlots(3));
        assertEquals(17, SkillRules.openSlots(4), "5 转预留：按 4 转算");
        assertEquals(5, SkillRules.openSlots(-1), "负值不炸，按未转职");
    }

    /** 全 11 职业 × 20 槽：id、编号、前置链与需求等级没有错位（off-by-one 只会在这种表上看出来）。 */
    @Test
    void 每个职业二十槽的编号与前置链() {
        for (int job = 1; job <= 11; job++) {
            Player p = new Player(0);
            p.setJob(job);
            p.setLevel(99);
            List<SkillDataRegistry.Skill> rows = data.ofJob(job);
            assertEquals(20, rows.size(), "职业 " + job + " 应 20 槽");
            for (int i = 0; i < rows.size(); i++) {
                SkillRules.Learn learn = SkillRules.resolve(p, data, rows.get(i).skillId());
                if (i >= 16) {
                    // 5 转：四格都未开放（没有价目表也没有点池）
                    assertEquals(SkillRules.Reason.SLOT_LOCKED, learn.reason(), "职业 " + job + " 槽 " + i);
                    continue;
                }
                assertTrue(learn.ok(), "职业 " + job + " 槽 " + i + " 应解析成功：" + learn.reason());
                assertEquals(i, learn.slotInJob(), "职业 " + job + " 槽 " + i + " 的序号");
                assertEquals(i / 4 + 1, learn.tier());
                assertEquals(i % 4 + 1, learn.slotInTier());
                assertEquals(i + 1, learn.skillNum(), "技能编号 = 序号 + 1");
                assertEquals(0, learn.currentPoint(), "没学过 = 0");
                assertEquals(i == 0 ? 0 : rows.get(i - 1).skillId(), learn.prevSkillId(),
                    "职业 " + job + " 槽 " + i + " 的上一槽");
                assertEquals(rows.get(i).reqLv(), learn.requireLevel());
                assertEquals(rows.get(i).constName(), learn.constName());
            }
        }
    }
}
