package org.jpstale.common.service.skill;

import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.item.ItemStorageService;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillMasteryService} 的特征测试：**两条写入路径**（道具 / GM 命令）的数值与门槛。
 *
 * <p>数据用**真**的 {@link SkillDataRegistry}（classpath 上有 `skilldata/skill-tables.json`），
 * 技能 id 从表里取（不手打），保证"断言里的 id 与数据里的 id"不会各错一半。
 */
class SkillMasteryServiceTest {

    private static SkillDataRegistry skillData;

    @BeforeAll
    static void load() {
        skillData = new SkillDataRegistry();
        skillData.load();
    }

    private static Player pikeman(int level) {
        Player p = new Player(0);
        p.setJob(4);
        p.setLevel(level);
        return p;
    }

    private static int idAt(int slot) {
        return skillData.ofJob(4).get(slot).skillId();
    }

    /** 无 Spring 容器时的惯例：反射注入 `skillData`（服务类不需要真容器）。 */
    private static SkillMasteryService service(ItemStorageService storage) {
        return new SkillMasteryService(skillData, new PlayerStatCalculator(), storage);
    }

    /** 一颗放在背包里的石头（uid 固定 77）。 */
    private static ItemInstance stoneInBag(Player p, int idcode) {
        ItemInstance it = new ItemInstance();
        it.setId(77L);
        it.setItemCode(idcode);
        it.setLocation(ItemLocations.BAG_PAGE);
        it.setSlot(0);
        p.getItems().byUidPut(it);
        return it;
    }

    /** `ItemStorageService.softDelete` 会落库 —— 测试里给个不落库的替身（只验流程与数值）。 */
    private static ItemStorageService noopStorage() {
        return new ItemStorageService(null) {
            @Override
            public void softDelete(long uid) {
                // 测试不落库
            }
        };
    }

    /** 有没有"石头被消耗"的痕迹（`deleted` 标记）。 */
    private static boolean consumed(ItemInstance stone) {
        return stone.isDeleted();
    }

    // ─────────────────────────── ① 物品码映射 ───────────────────────────

    /**
     * 三颗石头的 idcode —— 依据：11 职业 `OpenItem/BI139..BI141.txt` 的文件名就是
     * 「Skill Master(1st/2nd/3rd)」，而 `sinInvenTory.cpp:2425-2456` 用 `sinBI1 | sin39/40/41`
     * 分派（`sin39 = 0x3700`…，紧邻「Aging Master(A/B/C)」的 `sin36/37/38`）。
     */
    @Test
    void 三颗石头的码位与档位() {
        assertEquals(1, SkillMasteryService.tierIndexOf(0x080B3700));
        assertEquals(2, SkillMasteryService.tierIndexOf(0x080B3800));
        assertEquals(3, SkillMasteryService.tierIndexOf(0x080B3900));
        assertEquals(0, SkillMasteryService.tierIndexOf(null));
        assertEquals(0, SkillMasteryService.tierIndexOf(0x080B3600), "Aging Master(C) 不是熟练度石");
        assertEquals(0, SkillMasteryService.tierIndexOf(0x04020100), "药水更不是");
    }

    // ─────────────────────────── ② 道具：拉满一整档 ───────────────────────────

    /**
     * 效果逐字照抄 `HaPremiumItem.cpp:1877-1930`：**这一档的已学技能**计数直接 +10000（上限截断），
     * 别的档**一个都不许动**（档位边界：1 档 = 槽 1..4）。
     */
    @Test
    void 一档石头只拉满这一档且不动别的档() {
        Player p = pikeman(20);
        // 1 档 4 格全学；2 档第 1 格也学（用来验"没被误伤"）
        for (int slot = 0; slot < 4; slot++) {
            p.setPropInt(SkillKeys.point(idAt(slot)), 1);
        }
        int tier2Slot = 4;
        p.setPropInt(SkillKeys.point(idAt(tier2Slot)), 1);
        p.setTalent(30);                       // 才能项 = 10 ⇒ 派生 = 1000 + 计数
        ItemInstance stone = stoneInBag(p, 0x080B3700);

        SkillMasteryService.Result r = service(noopStorage()).matureTier(p, 77L);

        assertTrue(r.ok(), "门槛应通过（1 档有已学技能）");
        assertEquals(1, r.tier());
        // ⚠ 一档 4 格里有一格是**被动**（pikeman 槽 2 = ICE_ATTRIBUTE，`useCode = NOT`）——
        //    原版 `CheckMaturedSkill`/`UseSkillMaster` 都跳过它（`USECODE != SIN_SKILL_USE_NOT`）⇒ 只拉满 3 格。
        int passiveSlots = 0;
        for (int slot = 0; slot < 4; slot++) {
            if ("NOT".equals(skillData.ofJob(4).get(slot).useCode())) {
                passiveSlots++;
            }
        }
        assertEquals(1, passiveSlots, "前提：1 档恰有 1 个被动（数据变了这条要跟着改）");
        assertEquals(4 - passiveSlots, r.changed(), "非被动的 3 格各拉满一次");
        for (int slot = 0; slot < 4; slot++) {
            int id = idAt(slot);
            if ("NOT".equals(skillData.ofJob(4).get(slot).useCode())) {
                assertEquals(0, p.getPropInt(SkillKeys.mastery(id)), "被动不吃熟练度石（原版跳过）");
                continue;
            }
            // 计数写满（原版 `+= 10000` + 10000 上限）⇒ 派生值 = min(10000, 1000 + 10000) = 10000
            assertEquals(10000, p.getPropInt(SkillKeys.mastery(id)), "槽 " + slot + " 计数拉满");
            assertEquals(10000, SkillRules.useSkillMastery(p, skillData, id, 0),
                    "槽 " + slot + " 派生熟练度到顶（⇔ CD 最短）");
        }
        assertEquals(0, p.getPropInt(SkillKeys.mastery(idAt(tier2Slot))), "2 档的计数**不该被动**");
        assertTrue(consumed(stone), "石头被消耗");
    }

    /** 门槛：该档没有可提升的技能（未学 / 被动 / 已满）⇒ 拒绝，且石头**不消耗**（原版先 return）。 */
    @Test
    void 没有可提升的技能时拒绝且不消耗石头() {
        Player p = pikeman(20);
        p.setTalent(30);
        ItemInstance stone = stoneInBag(p, 0x080B3700);
        SkillMasteryService svc = service(noopStorage());

        // ① 一个都没学
        SkillMasteryService.Result r1 = svc.matureTier(p, 77L);
        assertFalse(r1.ok());
        assertEquals(SkillMasteryService.Reason.NOTHING_TO_MATURE, r1.reason());
        assertFalse(consumed(stone), "未消耗");

        // ② 只学了 1 档里的**被动**（`useCode = NOT`，原版 `USECODE != SIN_SKILL_USE_NOT` 会跳过）
        int passive = -1;
        for (SkillDataRegistry.Skill s : skillData.ofJob(4)) {
            if (s.slotInJob() < 4 && "NOT".equals(s.useCode())) {
                passive = s.skillId();
            }
        }
        if (passive >= 0) {
            p.setPropInt(SkillKeys.point(passive), 1);
            assertEquals(SkillMasteryService.Reason.NOTHING_TO_MATURE, svc.matureTier(p, 77L).reason(),
                    "被动不算“可提升”");
            assertFalse(consumed(stone));
        }
    }

    /** 石头不在背包/药水槽（例如放仓库）⇒ 拒绝。 */
    @Test
    void 石头必须在可用位置() {
        Player p = pikeman(20);
        p.setTalent(30);
        p.setPropInt(SkillKeys.point(idAt(0)), 1);
        ItemInstance stone = stoneInBag(p, 0x080B3700);
        stone.setLocation(ItemLocations.WAREHOUSE);

        SkillMasteryService.Result r = service(noopStorage()).matureTier(p, 77L);
        assertEquals(SkillMasteryService.Reason.STONE_NOT_IN_BAG, r.reason());
        assertFalse(consumed(stone));
    }

    // ─────────────────────────── ③ GM 命令：设百分比 ───────────────────────────

    /**
     * `/@skill_mastery 100` ⇒ 已学技能计数 = `10000 − 才能项` ⇒ 派生值正好 10000（满）。
     * 未学技能**不建键**（"没学"就是键不存在，不写 0 进去）。
     */
    @Test
    void GM设为百分之百时派生值到顶且只碰已学技能() {
        Player p = pikeman(20);
        p.setTalent(30);                       // 才能项 10 ⇒ 计数应写 9000
        p.setPropInt(SkillKeys.point(idAt(0)), 1);
        p.setPropInt(SkillKeys.mastery(idAt(0)), 123);

        SkillMasteryService.Result r = service(noopStorage()).setAllPercent(p, 100);

        assertTrue(r.ok());
        assertEquals(1, r.changed());
        assertEquals(100, r.effectivePct());
        assertEquals(9000, p.getPropInt(SkillKeys.mastery(idAt(0))));
        assertEquals(10000, SkillRules.useSkillMastery(p, skillData, idAt(0), 0));
        assertFalse(p.getProps().containsKey(SkillKeys.mastery(idAt(1))), "未学的技能不建键");
    }

    /**
     * 才能/装备给的**下限**高于目标时，计数只能夹到 0 ⇒ 实际百分比**更大**。
     * 这条是 AGENTS #12 的"不许假装做到"：`effectivePct` 必须如实回大值。
     */
    @Test
    void GM目标低于才能下限时如实回报实际值() {
        Player p = pikeman(20);
        p.setTalent(90);                       // 才能项 30 ⇒ 下限 3000（30%）
        p.setPropInt(SkillKeys.point(idAt(0)), 1);
        p.setPropInt(SkillKeys.mastery(idAt(0)), 9999);

        SkillMasteryService.Result r = service(noopStorage()).setAllPercent(p, 5);

        assertTrue(r.ok());
        assertEquals(0, p.getPropInt(SkillKeys.mastery(idAt(0))), "计数夹到 0");
        assertEquals(3000, SkillRules.useSkillMastery(p, skillData, idAt(0), 0), "派生值 = 才能下限");
        assertEquals(30, r.effectivePct(), "实际 30%（要不到 5%）—— 必须如实回报");
    }

    /** 参数越界 ⇒ `BAD_PERCENT`（GM 命令回参数错误，一个技能都不动）。 */
    @Test
    void GM参数必须在1到100之间() {
        Player p = pikeman(20);
        p.setPropInt(SkillKeys.point(idAt(0)), 1);
        SkillMasteryService svc = service(noopStorage());
        assertEquals(SkillMasteryService.Reason.BAD_PERCENT, svc.setAllPercent(p, 0).reason());
        assertEquals(SkillMasteryService.Reason.BAD_PERCENT, svc.setAllPercent(p, 101).reason());
        assertFalse(p.getProps().containsKey(SkillKeys.mastery(idAt(0))));
    }

    /**
     * `Element[0] != 0` 的技能（高转职段）派生值**恒 10000** ⇒ GM 命令**不动它的计数**
     * （写下去只会抹掉真实的修炼记录，显示值分毫不变），只回报数量。
     */
    @Test
    void GM不碰恒满的元素技能() {
        Player p = pikeman(20);
        p.setTalent(30);
        int element = -1, normal = -1;
        for (SkillDataRegistry.Skill s : skillData.ofJob(4)) {
            if (s.element0() != 0 && element < 0) {
                element = s.skillId();
            }
            if (s.element0() == 0 && normal < 0) {
                normal = s.skillId();
            }
        }
        assertTrue(element > 0 && normal > 0, "pikeman 两种技能都要有（否则这条测试没意义）");
        p.setPropInt(SkillKeys.point(element), 1);
        p.setPropInt(SkillKeys.point(normal), 1);
        p.setPropInt(SkillKeys.mastery(element), 400);

        SkillMasteryService.Result r = service(noopStorage()).setAllPercent(p, 40);

        assertEquals(400, p.getPropInt(SkillKeys.mastery(element)), "元素技能的计数不许被抹掉");
        assertEquals(10000, SkillRules.useSkillMastery(p, skillData, element, 0), "它本来就恒满");
        assertEquals(1, r.elementFull(), "恒满的数量如实回报");
        assertEquals(1, r.changed(), "只改了普通技能");
        assertEquals(3000, p.getPropInt(SkillKeys.mastery(normal)), "目标 40% ⇒ 计数 = 4000 − 1000（才能项）");
        assertEquals(40, r.effectivePct());
    }

    /** 三个 key 的文案后缀（协议用它取文案，不能是 null 或空）。 */
    @Test
    void 原因码都有文案key() {
        for (SkillMasteryService.Reason r : SkillMasteryService.Reason.values()) {
            if (r == SkillMasteryService.Reason.OK) {
                assertEquals(null, r.key());
            } else {
                assertTrue(r.key() != null && r.key().startsWith("item.op.skill-master."),
                        r + " 的 key 应是 item.op.skill-master.*");
            }
        }
    }
}
