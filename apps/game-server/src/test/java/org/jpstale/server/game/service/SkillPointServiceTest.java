package org.jpstale.server.game.service;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.common.service.skill.SkillRules;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.proto.base.S2C_SkillList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillPointService} 的特征测试 —— 技能点是**派生量**（等级 + 任务位 − 已花），不落库。
 *
 * <p>数据用**真**的 {@link SkillDataRegistry}（classpath 上有 `skilldata/skill-tables.json`），
 * 技能 id 与需求等级从表里取（不手打，避免"断言里的 id 与数据里的 id 各错一半"）。
 */
class SkillPointServiceTest {

    private static SkillDataRegistry skillData;

    @BeforeAll
    static void load() {
        skillData = new SkillDataRegistry();
        skillData.load();     // Spring 下是 @PostConstruct；这里显式调（与本模块其它测试同风格）
    }

    private static Player pikeman(int level) {
        Player p = new Player(0);
        p.setJob(4);
        p.setLevel(level);
        return p;
    }

    /** 该职业第 n 个槽（0 基，按槽序）的技能 id —— 顺带钉住"槽序 = 面板序"。 */
    private static int idAt(int index) {
        SkillDataRegistry.Skill s = skillData.ofJob(4).get(index);
        assertEquals(index, s.slotInJob(), "槽 " + index + " 的槽序");
        assertEquals(index / 4 + 1, s.tier(), "槽 " + index + " 的转职档（每档 4 槽）");
        assertEquals(index % 4 + 1, s.slotInTier(), "槽 " + index + " 在档内的位置");
        return s.skillId();
    }


    /**
     * 造一个注入了 `skillData` 的 {@link SkillCastService}（无 Spring 容器时的惯例：反射注入，
     * 同 `DamageCalculatorTest`）。`buildSkillList` 会经它读"伤害百分比"（面板数据）。
     */
    private static SkillCastService castService(SkillDataRegistry data) {
        SkillCastService svc = new SkillCastService();
        try {
            java.lang.reflect.Field f = SkillCastService.class.getDeclaredField("skillData");
            f.setAccessible(true);
            f.set(svc, data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 skillData 失败", e);
        }
        return svc;
    }

    private final SkillPointService svc = new SkillPointService(skillData, new PlayerStatCalculator(), castService(skillData));

    @Test
    void 二十级Pikeman一池六点四池零() {
        Player p = pikeman(20);
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE), "(20-8)/2");
        assertEquals(0, svc.free(p, SkillPointService.Pool.FOUR), "四转池的门是 60 级");
    }

    @Test
    void 学过三级就少三点() {
        Player p = pikeman(20);
        p.setPropInt(SkillKeys.point(idAt(0)), 3);
        assertEquals(3, svc.free(p, SkillPointService.Pool.ONE));
    }

    @Test
    void 四转的点只从四池扣() {
        Player p = pikeman(70);
        assertEquals(31, svc.free(p, SkillPointService.Pool.ONE), "(70-8)/2");
        assertEquals(6, svc.free(p, SkillPointService.Pool.FOUR), "(70-58)/2");

        p.setPropInt(SkillKeys.point(idAt(12)), 2);   // tier 4 槽 1
        assertEquals(4, svc.free(p, SkillPointService.Pool.FOUR));
        assertEquals(31, svc.free(p, SkillPointService.Pool.ONE), "一池不该被四转的技能扣点");
    }

    @Test
    void 任务位加一池的点() {
        Player p = pikeman(20);
        p.setPropInt(PlayerKey.QUEST_LEVEL_55.getKey(), 1);
        assertEquals(7, svc.free(p, SkillPointService.Pool.ONE));
        assertEquals(0, svc.free(p, SkillPointService.Pool.FOUR), "任务位不给四池");
    }

    @Test
    void 九级两池都是零() {
        Player p = pikeman(9);
        assertEquals(0, svc.free(p, SkillPointService.Pool.ONE), "门槛之下是 0，不是负数");
        assertEquals(0, svc.free(p, SkillPointService.Pool.FOUR));

        // 门槛之下的等级连"已花"都不必看：写了技能等级也是 0 点可用
        p.setPropInt(SkillKeys.point(idAt(0)), 3);
        assertEquals(0, svc.free(p, SkillPointService.Pool.ONE));
    }

    @Test
    void 没有Player就抛() {
        assertThrows(IllegalArgumentException.class, () -> svc.free(null, SkillPointService.Pool.ONE));
    }

    /* ─────────────── 学 / 升级（P1） ─────────────── */

    /** 20 级、钱管够的 pikeman（一池 6 点）。 */
    private static Player rich() {
        Player p = pikeman(20);
        p.setGold(100_000);
        return p;
    }

    @Test
    void 学一级_点数少一_等级写进props的键() {
        Player p = rich();
        SkillPointService.LearnResult r = svc.learn(p, idAt(0));

        assertEquals(SkillRules.Reason.OK, r.reason());
        assertEquals(500, r.cost(), "第 1 级 = 基数 500 + 增量×0");
        assertEquals(1, p.getPropInt("skill.0x040101.point"), "等级写进 props（键 = skill.<0xID>.point）");
        assertEquals(1, p.getPropInt(SkillKeys.point(idAt(0))), "查询口拼的是同一个键");
        assertEquals(5, svc.free(p, SkillPointService.Pool.ONE), "20 级一池 6 点，学一级少一");
    }

    @Test
    void 再学一次_等级到二_价钱按当前等级加档() {
        Player p = rich();
        long first = svc.learn(p, idAt(0)).cost();
        SkillPointService.LearnResult second = svc.learn(p, idAt(0));

        assertEquals(SkillRules.Reason.OK, second.reason(), "20 级够升第 2 级（10 + 1×2 = 12）");
        assertEquals(1000, second.cost(), "第 2 级 = 500 + 500×1");
        assertEquals(2, p.getPropInt(SkillKeys.point(idAt(0))));
        assertEquals(4, svc.free(p, SkillPointService.Pool.ONE));
        assertEquals(1500, first + second.cost(), "两次一共收这么多");
    }

    /* ─────────────── 每个拒绝原因一个用例 ─────────────── */

    @Test
    void 未知id被拒() {
        Player p = rich();
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, svc.learn(p, 0x040521).reason(), "槽 33 不是技能");
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, svc.learn(p, 0).reason());
        assertEquals(SkillRules.Reason.UNKNOWN_SKILL, svc.learn(p, -1).reason());
        assertEquals(0, p.getProps().size(), "被拒不许写 props");
    }

    @Test
    void 非本职业的id被拒() {
        Player p = rich();
        assertEquals(SkillRules.Reason.WRONG_JOB, svc.learn(p, SkillIds.RAVING.id()).reason(),
                "fighter 的技能（id 的职业段≠4）");
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE));
    }

    @Test
    void 槽位没开被拒() {
        // 1 转只开 0..4：前 4 槽学会后，第 5 槽（0 基 5）唯一被拒的原因是"槽没开"
        Player p = pikeman(30);
        p.setGold(100_000);
        for (int i = 0; i < 4; i++) {
            p.setPropInt(SkillKeys.point(idAt(i)), 1);
        }
        assertEquals(SkillRules.Reason.SLOT_LOCKED, svc.learn(p, idAt(5)).reason());
    }

    @Test
    void 五转四格被拒_不抛() {
        // 槽序 16..19 既无点池也无价目表 ⇒ 必须得到 SLOT_LOCKED（而不是让 free(p, null) 抛）
        Player p = pikeman(99);
        p.setGold(9_999_999);
        p.setRank(4);
        assertNull(skillData.ofJob(4).get(16).macro(), "五转没有源码宏");
        assertEquals(SkillRules.Reason.SLOT_LOCKED, svc.learn(p, idAt(16)).reason());
        assertEquals(SkillRules.Reason.SLOT_LOCKED, svc.learn(p, idAt(19)).reason());
    }

    @Test
    void 上一槽未学被拒_且不写props() {
        Player p = rich();
        SkillPointService.LearnResult r = svc.learn(p, idAt(1));   // 第 2 槽

        assertEquals(SkillRules.Reason.PREV_NOT_LEARNED, r.reason());
        assertEquals(0, p.getPropInt(SkillKeys.point(idAt(1))));
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE), "被拒不该动点数");
    }

    @Test
    void 等级不够被拒() {
        Player p = pikeman(skillData.ofJob(4).get(0).reqLv() - 1);
        p.setGold(100_000);
        assertEquals(SkillRules.Reason.LEVEL_TOO_LOW, svc.learn(p, idAt(0)).reason());
    }

    @Test
    void 点数不足被拒() {
        // 12 级一池只有 2 点，全花在第 1 槽上 ⇒ 第 2 槽的门槛都够、只有点数为 0
        Player p = pikeman(12);
        p.setGold(100_000);
        p.setPropInt(SkillKeys.point(idAt(0)), 2);
        assertEquals(0, svc.free(p, SkillPointService.Pool.ONE));
        assertEquals(SkillRules.Reason.NO_SKILL_POINT, svc.learn(p, idAt(1)).reason());
    }

    @Test
    void 钱不够被拒() {
        Player p = pikeman(20);
        p.setGold(499);
        assertEquals(SkillRules.Reason.NO_GOLD, svc.learn(p, idAt(0)).reason());
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE));
    }

    @Test
    void 满十级被拒() {
        Player p = pikeman(skillData.ofJob(4).get(0).reqLv() + 20);
        p.setGold(999_999);
        p.setPropInt(SkillKeys.point(idAt(0)), SkillRules.MAX_POINT);
        assertEquals(SkillRules.Reason.MAX_POINT, svc.learn(p, idAt(0)).reason());
    }

    @Test
    void 退回一级() {
        Player p = rich();
        svc.learn(p, idAt(0));
        assertEquals(1, p.getPropInt(SkillKeys.point(idAt(0))));
        svc.undoLearn(p, idAt(0));
        assertEquals(0, p.getPropInt(SkillKeys.point(idAt(0))), "扣钱被拒时的回滚");
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE));

        // 没学过的与陌生的 id：退回是空操作（不是把等级退成 -1）
        svc.undoLearn(p, idAt(1));
        svc.undoLearn(p, 0x040521);
        assertEquals(0, p.getPropInt(SkillKeys.point(idAt(1))));
    }

    /* ─────────────── 洗点 ─────────────── */

    @Test
    void 洗点清零_同会话第二次被拒_重登后可再洗() {
        Player p = rich();
        svc.learn(p, idAt(0));
        svc.learn(p, idAt(0));
        svc.learn(p, idAt(1));
        p.setPropInt(SkillKeys.mastery(idAt(0)), 1500);
        int beforeFree = svc.free(p, SkillPointService.Pool.ONE);
        assertEquals(3, beforeFree, "3 点花出去了");
        assertEquals(1500, p.getPropInt(SkillKeys.mastery(idAt(0))));
        assertEquals(1, p.getPropInt(SkillKeys.point(idAt(1))));

        assertEquals(SkillRules.Reason.OK, svc.reset(p));
        assertEquals(0, p.getPropInt(SkillKeys.point(idAt(0))));
        assertEquals(0, p.getPropInt(SkillKeys.mastery(idAt(0))), "熟练度一起清");
        assertEquals(0, p.getPropInt(SkillKeys.point(idAt(1))));
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE), "清键即退点，回到上限");
        // "全清" = 该职业 20 格的等级/熟练度**一个不留**（清零，不是把键从包里删掉：
        // 残留的 0 不参与任何判据，见 clearIfPresent）
        for (int i = 0; i < 20; i++) {
            assertEquals(0, p.getPropInt(SkillKeys.point(idAt(i))), "槽 " + i + " 的等级");
            assertEquals(0, p.getPropInt(SkillKeys.mastery(idAt(i))), "槽 " + i + " 的熟练度");
        }
        for (String k : skillKeysOf(p)) {
            assertEquals(0, p.getProps().get(k), "洗点后残留的 skill.* 键必须是 0：" + k);
        }

        assertEquals(SkillRules.Reason.RESET_USED, svc.reset(p), "本次登录只准洗一次");

        // 重登 = 换一个 Player（守卫是内存标记、不落库）：新对象没有标记 ⇒ 可以再洗
        Player relogged = rich();
        assertEquals(SkillRules.Reason.OK, svc.reset(relogged));
    }

    @Test
    void 洗点不动没学的键() {
        Player p = rich();
        svc.learn(p, idAt(0));
        int keys = p.getProps().size();
        svc.reset(p);
        assertEquals(keys, p.getProps().size(), "只清已经存在的键，不往里塞 0");
    }

    /** 包里 `skill.*` 的键（升序）—— 洗点的判据就是"它们全清"。 */
    private static List<String> skillKeysOf(Player p) {
        return p.getProps().keySet().stream().filter(k -> k.startsWith("skill.")).sorted().toList();
    }

    /* ─────────────── 升级路径：一个 props 键都不写 ─────────────── */

    @Test
    void 升级只改等级_不写任何props键() {
        Player p = pikeman(10);
        assertEquals(1, svc.free(p, SkillPointService.Pool.ONE), "(10-8)/2");
        Map<String, Integer> before = new LinkedHashMap<>(p.getProps());

        p.setLevel(20);   // 升级（原版就是只改 Level，技能点由等级派生）
        assertEquals(6, svc.free(p, SkillPointService.Pool.ONE));
        assertEquals(before, p.getProps(), "升级路径零写入");
    }

    /* ─────────────── 下发 ─────────────── */

    @Test
    void 技能表只含已学的_标识是数字id() {
        Player p = rich();
        assertTrue(svc.buildSkillList(p).getSkillsList().isEmpty(), "没学过 = 空表");
        assertEquals(6, svc.buildSkillList(p).getSkillPoint());
        assertEquals(0, svc.buildSkillList(p).getSpecialSkillPoint(), "20 级够不到 4 转池");

        svc.learn(p, idAt(0));
        svc.learn(p, idAt(0));
        p.setPropInt(SkillKeys.mastery(idAt(0)), 700);
        org.jpstale.server.proto.base.S2C_SkillList list = svc.buildSkillList(p);
        assertEquals(1, list.getSkillsList().size());
        assertEquals(idAt(0), list.getSkills(0).getSkillId(), "标识是数字 id，不是宏名也不是动画下标");
        assertEquals(SkillIds.PIKE_WIND.id(), list.getSkills(0).getSkillId());
        assertEquals(2, list.getSkills(0).getPoint());
        assertEquals(700, list.getSkills(0).getMastery());
        assertEquals(4, list.getSkillPoint());
    }

    @Test
    void 载入自检只报错不改数据() {
        assertEquals(0, svc.checkLoaded(rich()), "正常角色不报（四池没过等级门 ≠ 超花）");

        // 20 级只有 6 点，却已经花了 8 点（存档非法）—— 报错但不动数据
        Player bad = pikeman(20);
        bad.setPropInt(SkillKeys.point(idAt(0)), 8);
        assertEquals(1, svc.checkLoaded(bad), "一池超花");
        assertEquals(8, bad.getPropInt(SkillKeys.point(idAt(0))), "只报错，不改数据");
    }

    /**
     * 面板数据（`S2C_SkillList.learn_info`）：学**下一级**的等级门槛/金币 + **伤害百分比**。
     *
     * 钉住两件事（都是"错了不会报错、只是面板显示假数"的那类）：
     * ① Pike Wind 是"攻击力 ×(1+%)"模型 ⇒ 要给出百分比，且 1 级 = `Pike_Wind_Damage[0]` = 3..20；
     * ② Critical Hit 不是那个模型（它的模型是"暴击率 +表值"）⇒ **不给**百分比（面板就不显示那一行，
     *    而不是编一个数出来）。
     */
    @Test
    void 面板数据给出下一级条件与伤害百分比() {
        Player p = pikeman(20);
        int pikeWind = idAt(0);
        int criticalHit = idAt(2);
        p.setPropInt(SkillKeys.point(pikeWind), 1);
        p.setPropInt(SkillKeys.point(criticalHit), 1);

        S2C_SkillList list = svc.buildSkillList(p);
        var info = list.getLearnInfoList().stream().collect(
                java.util.stream.Collectors.toMap(i -> i.getSkillId(), i -> i));

        var pw = info.get(pikeWind);
        assertNotNull(pw, "Pike Wind 的面板数据");
        assertEquals(3, pw.getPowerPctMin(), "1 级下限 = Pike_Wind_Damage[0][0] = 3");
        assertEquals(20, pw.getPowerPctMax(), "1 级上限 = Pike_Wind_Damage[0][1] = 20");
        assertEquals(5, pw.getNextPowerPctMin(), "2 级下限 = Pike_Wind_Damage[1][0] = 5");
        assertEquals(skillData.byId(pikeWind).reqLv() + 1 * 2, pw.getNextReqLevel(),
                "下一级所需等级 = RequireLevel + 当前等级*2（从数据行算，不手抄等级）");
        assertTrue(pw.getNextGold() > 0, "下一级金币要给出");

        var ch = info.get(criticalHit);
        assertNotNull(ch);
        assertEquals(0, ch.getPowerPctMin(), "Critical Hit 不是'攻击力×百分比'模型 ⇒ 不报百分比");
        assertEquals(0, ch.getPowerPctMax());
    }

    /**
     * **熟练度增长**（原版 `Morayion.cpp:281-288` 逐字）：
     * `UseSKillIncreCount++`，达 `sinMasteryIncreaIndex[槽] + (Point-1)/3` ⇒ 计数清零 + 熟练度 +100。
     *
     * 钉住三件事（前两条是"错了不会报错、只是永远不涨/涨错"的那类）：
     * ① 门槛**按槽位**（表 `{5,5,5,5,7,7,7,7,9,9,9,9,14,15,16,17}`）+ `(Point-1)/3`；
     * ② 达标那一次熟练度 **+100**、计数清零；未达标只 +1 计数（熟练度不动）；
     * ③ 熟练度满 10000 后**不再计数**（原版同样有 `< 10000` 的前提）。
     */
    @Test
    void 熟练度按原版门槛增长() {
        Player p = pikeman(20);
        int sid = idAt(0);                       // 槽 1（门槛 5，Point=1 ⇒ 5 + 0 = 5）
        p.setPropInt(SkillKeys.point(sid), 1);

        for (int i = 1; i <= 4; i++) {
            assertFalse(svc.growMastery(p, sid), "第 " + i + " 次不该涨（门槛 5）");
        }
        assertEquals(4, p.getPropInt(SkillKeys.useCount(sid)), "计数应记到 4");
        assertEquals(0, p.getPropInt(SkillKeys.mastery(sid)), "还没到门槛");

        assertTrue(svc.growMastery(p, sid), "第 5 次达标 ⇒ 涨一次");
        assertEquals(0, p.getPropInt(SkillKeys.useCount(sid)), "达标后计数清零");
        assertEquals(100, p.getPropInt(SkillKeys.mastery(sid)), "涨 100（USE_SKILL_MASTERY_COUNT）");

        // ③ 满熟练度后不再计数
        p.setPropInt(SkillKeys.mastery(sid), 10000);
        p.setPropInt(SkillKeys.useCount(sid), 0);
        for (int i = 0; i < 10; i++) {
            assertFalse(svc.growMastery(p, sid), "满熟练度不再涨");
        }
        assertEquals(0, p.getPropInt(SkillKeys.useCount(sid)), "满熟练度时连计数都不记");
        assertEquals(10000, p.getPropInt(SkillKeys.mastery(sid)), "上限 10000");
    }

    /**
     * **熟练度的派生值**（面板与 CD 用的那个"熟练度"）—— 逐字照抄原版
     * `sinSkill.cpp:2061-2071`：`min(50, Talent/3 + fMagic_Mastery) × 100 + 原始计数`，
     * 且 `Element[0] != 0` 的技能**恒满 10000**、整体上限 10000。
     *
     * <p>为什么值得钉：早先我们**只发原始计数**（少了 `Talent/3×100`，最多 +5000），于是 CD 公式里的
     * `− 熟练度/100` 几乎不动、被 70 档上限吃掉 ⇒ 用户实测"CD 完全不受熟练度影响"。
     */
    @Test
    void 熟练度派生含Talent项且元素技能恒满() {
        Player p = pikeman(20);
        int sid = idAt(0);                       // 龙卷枪风：element0 = 0（T1 段）
        assertEquals(0, skillData.byId(sid).element0(), "前提：槽 1 不是 Element[0] 技能");
        p.setTalent(30);
        p.setPropInt(SkillKeys.point(sid), 1);
        p.setPropInt(SkillKeys.mastery(sid), 400);

        // Talent/3 = 10 ⇒ 10×100 + 400 = 1400（装备没给魔法精通 ⇒ 0 是"没有加成"，不是换值）
        assertEquals(1400, SkillRules.useSkillMastery(p, skillData, sid, 0));
        // 装备的 fMagic_Mastery（原版 sinAdd_fMagic_Mastery）是**加法项**，直接叠在 Talent/3 上
        assertEquals(1900, SkillRules.useSkillMastery(p, skillData, sid, 5));
        // `TempTalent > 50` 截断（`:2062`）：Talent 999/3 = 333、再 +5 ⇒ 50
        p.setTalent(999);
        assertEquals(5000 + 400, SkillRules.useSkillMastery(p, skillData, sid, 5), "Talent/3 项上限 50");
        // 上限 10000（`:2069`）
        p.setPropInt(SkillKeys.mastery(sid), 9999);
        assertEquals(10000, SkillRules.useSkillMastery(p, skillData, sid, 5));

        // Element[0] 技能：**不看** Talent/计数，恒满（`:2064`）
        int el = idAt(12);                       // 刺客之眼/4 转段 = Element[0]
        assertEquals(1, skillData.byId(el).element0(), "前提：槽 13 是 Element[0] 技能");
        p.setTalent(0);
        p.setPropInt(SkillKeys.mastery(el), 0);
        assertEquals(10000, SkillRules.useSkillMastery(p, skillData, el, 0), "元素/高阶技能熟练度恒满");
    }

    /** 下发的那一列就是**派生值**（不是 props 里的原始计数）—— 客户端面板的百分比与 CD 都读它。 */
    @Test
    void 技能表下发的熟练度是派生值() {
        Player p = pikeman(20);
        int sid = idAt(0);
        p.setTalent(30);
        p.setPropInt(SkillKeys.point(sid), 1);
        p.setPropInt(SkillKeys.mastery(sid), 400);

        S2C_SkillList m = svc.buildSkillList(p);
        var row = m.getSkillsList().stream().filter(x -> x.getSkillId() == sid).findFirst().orElseThrow();
        assertEquals(1400, row.getMastery(), "发的是 UseSkillMastery（含 Talent/3×100），不是存下来的 400");
        assertEquals(400, p.getPropInt(SkillKeys.mastery(sid)), "props 里存的仍是原始计数（增长要用）");
    }
}
