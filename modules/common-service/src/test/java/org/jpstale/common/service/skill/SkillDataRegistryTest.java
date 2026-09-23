package org.jpstale.common.service.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillDataRegistry} 的特征测试 —— 钉住"服务端读到的是哪份数据"。
 *
 * ⚠ 期望值**独立誊写**（来自原版源码与 `SkillDataRegistry` 的产物口径），不是从 JSON 反读出来的；
 * 抽取/载入与断言对不上时**改实现，别改断言**（对照客户端 `verify-skill-tables` 的同一纪律）。
 *
 * 数据面：`.refsrc/tree` 的 `sinbaram/sinSkill_Info.cpp`（参数表）、`sinbaram/sinSkill.h`（宏）、
 * `Language/Brazil/b_sinSkill_Info.h`（定义）；计数 = 491 表 / 202 宏 / 198 Brazil / 151 English。
 * 另有生成物自己的 **`skills` 段 220 行**（我方技能身份：数字 id + 面板槽 + 图标名），与
 * `SkillIds.java` 的 220 个常量必须是同一套。
 */
class SkillDataRegistryTest {

    /** fighter（`GROUP_FIGHTER`）16 个技能，按 (tier, slotInTier) 顺序 —— 宏名 + 需求等级。 */
    private static final String[] FIGHTER_MACROS = {
            "SKILL_MELEE_MASTERY", "SKILL_FIRE_ATTRIBUTE", "SKILL_RAVING", "SKILL_IMPACT",
            "SKILL_TRIPLE_IMPACT", "SKILL_BRUTAL_SWING", "SKILL_ROAR", "SKILL_RAGE_OF_ZECRAM",
            "SKILL_CONCENTRATION", "SKILL_AVANGING_CRASH", "SKILL_SWIFT_AXE", "SKILL_BONE_CRASH",
            "SKILL_DETORYER", "SKILL_BERSERKER", "SKILL_CYCLONE_STRIKE", "SKILL_BOOST_HEALTH",
    };
    private static final int[] FIGHTER_REQUIRE_LEVEL = {
            10, 12, 14, 17, 20, 23, 26, 30, 40, 43, 46, 50, 60, 63, 66, 70,
    };

    private static SkillDataRegistry reg;

    @BeforeAll
    static void load() {
        reg = new SkillDataRegistry();
        reg.load();     // Spring 下是 @PostConstruct；这里显式调（与本模块其它测试同风格）
    }

    @Test
    void 载入并校验计数() {
        SkillDataRegistry.Counts c = reg.counts();
        assertEquals(491, c.arraysTotal(), "参数表总数（451 一维 int + 33 二维 int + 7 float）");
        assertEquals(202, c.macros(), "技能宏");
        assertEquals(198, c.defsBrazil(), "Brazil 技能定义");
        assertEquals(151, c.defsEnglish(), "English 技能定义");
        assertNotNull(reg.sourceHash());
        assertFalse(reg.sourceHash().isBlank(), "sourceHash 是两端判同代的依据，不能为空");
    }

    @Test
    void fighter十六个宏可按组与槽位取到() {
        List<SkillDataRegistry.SkillSlot> slots = reg.skillsOfGroup("GROUP_FIGHTER");
        assertEquals(16, slots.size(), "4 转 × 每转 4 槽");
        int i = 0;
        for (int tier = 1; tier <= 4; tier++) {
            for (int slotInTier = 1; slotInTier <= 4; slotInTier++) {
                String macro = FIGHTER_MACROS[i++];
                SkillDataRegistry.SkillSlot byList = slots.get(i - 1);
                assertEquals(tier, byList.tier());
                assertEquals(slotInTier, byList.slotInTier());
                assertEquals(macro, byList.macro());
                SkillDataRegistry.SkillSlot bySlot = reg.skill("GROUP_FIGHTER", tier, slotInTier);
                assertEquals(macro, bySlot.macro(), "按 group+tier+slotInTier 取到的应是同一个技能");
                assertEquals(byList.value(), bySlot.value());
                assertEquals(byList.definition().code(), bySlot.definition().code());
            }
        }
        assertEquals(16, i);
    }

    @Test
    void fighter十六个技能的需求等级() {
        for (int i = 0; i < FIGHTER_MACROS.length; i++) {
            SkillDataRegistry.SkillDefinition def = reg.definition(FIGHTER_MACROS[i]);
            assertEquals(FIGHTER_REQUIRE_LEVEL[i], def.requireLevel(),
                    FIGHTER_MACROS[i] + " 的 RequireLevel（tuple[2]）");
        }
    }

    @Test
    void Raving的定义逐字段钉住() {
        SkillDataRegistry.SkillDefinition def = reg.definition("SKILL_RAVING");
        assertEquals("SKILL_RAVING", def.code());
        assertEquals(14, def.requireLevel());
        assertArrayEquals(new int[]{35, 2}, def.useStamina(), "UseStamina[2] = {基础, 每级增量}");
        assertArrayEquals(new int[]{93, 3}, def.requireMastery());
        assertArrayEquals(new int[]{1, 0, 0}, def.element());
        assertEquals(List.of("sinWA1", "sinWC1", "sinWH1", "sinWP1", "sinWS2"), def.weaponCodes(),
                "UseWeaponCode[8] 里写了 0 的槽不算武器族");
        assertEquals("SIN_SKILL_USE_ALL", def.useCode());
        assertEquals("Raving_UseMana", def.useManaTable(), "tuple[21] = MP 消耗表名");
        assertTrue(def.src().endsWith("b_sinSkill_Info.h:106"), "出处：" + def.src());
    }

    @Test
    void Raving_UseLife取值() {
        assertArrayEquals(new double[]{1, 1.3, 1.6, 1.9, 2.2, 2.5, 2.8, 3.1, 3.4, 3.7},
                reg.table1d("Raving_UseLife"), 1e-9);
    }

    @Test
    void Raving_Damage存在且长度十() {
        // fighter 用：Raving 的伤害表（下标 = 技能等级 − 1）
        assertEquals(10, reg.table1d("Raving_Damage").length);
        assertTrue(reg.hasTable("Raving_Damage"));
    }

    @Test
    void PlusFire长度十() {
        assertEquals(10, reg.table1d("PlusFire").length);
    }

    @Test
    void Cyclone_Strike_AreaDamage是十行二维表() {
        double[][] v = reg.table2d("Cyclone_Strike_AreaDamage");
        assertEquals(10, v.length);
        assertArrayEquals(new double[]{120, 150}, v[0]);
        assertArrayEquals(new double[]{255, 285}, v[9]);
    }

    @Test
    void 表出处可倒查回源码行() {
        assertTrue(reg.tableSource("Raving_UseLife").matches("sinbaram/sinSkill_Info\\.cpp:\\d+"),
                "实得：" + reg.tableSource("Raving_UseLife"));
    }

    @Test
    void 取不到的东西必须抛而不是给null或空() {
        // 表名写错 ⇒ 抛，消息里要有那个名字（AGENTS #12：不许静默兜底）
        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> reg.table1d("Raving_Damage2"));
        assertTrue(e1.getMessage().contains("Raving_Damage2"), e1.getMessage());

        // 维度用错 ⇒ 抛（二维表不能用 table1d 取）
        assertThrows(IllegalStateException.class, () -> reg.table1d("Cyclone_Strike_AreaDamage"));
        // 一维表不能用 table2d 取
        assertThrows(IllegalStateException.class, () -> reg.table2d("Raving_Damage"));

        // 真正"只有宏、没有定义"的 4 个（其余 198 个宏的定义都在，含 CODE 不叫 SKILL_* 的那批）⇒ 抛
        IllegalStateException e2 = assertThrows(IllegalStateException.class,
                () -> reg.definition("BUFF_WARMODE"));
        assertTrue(e2.getMessage().contains("BUFF_WARMODE"), e2.getMessage());

        // ⚠ 反面钉子：CODE 不以 SKILL_ 开头的定义**是存在的**（生成物顶层的 `definitions[].code`
        //   只对 SKILL_* 有值 ⇒ 照那个字段索引会误判成"没有定义"）。这里钉住按 tuple[19] 索引这条口径。
        assertEquals("SCROLL_INVULNERABILITY", reg.definition("SCROLL_INVULNERABILITY").code());
        assertTrue(reg.definition("SCROLL_INVULNERABILITY").src().endsWith("b_sinSkill_Info.h:874"),
                "出处：" + reg.definition("SCROLL_INVULNERABILITY").src());

        // 完全不存在的宏名
        assertThrows(IllegalStateException.class, () -> reg.definition("SKILL_NOT_A_SKILL"));
        // 不存在的槽位
        assertThrows(IllegalStateException.class, () -> reg.skill("GROUP_FIGHTER", 5, 1));
        // 还没 load() 的空壳不许返回 null
        SkillDataRegistry fresh = new SkillDataRegistry();
        assertThrows(IllegalStateException.class, fresh::counts);
    }

    /* ─────────────── skills 段：220 行身份表（协议/props 键/判据都用它） ─────────────── */

    private static final int PIKEMAN = 4;

    /** 全部 220 行的 id（按职业 × 槽序汇总 —— 顺带证明"每职业 20 格"）。 */
    private static Set<Integer> tableIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int job = 1; job <= 11; job++) {
            List<SkillDataRegistry.Skill> rows = reg.ofJob(job);
            assertEquals(20, rows.size(), "职业 " + job + " 应 20 格");
            for (SkillDataRegistry.Skill s : rows) {
                ids.add(s.skillId());
            }
        }
        return ids;
    }

    @Test
    void 技能表两百二十行_每职业二十格() {
        Set<Integer> ids = tableIds();
        assertEquals(220, ids.size(), "11 职业 × 20 槽，且 id 不重复");
    }

    @Test
    void 按id与按槽取到同一行() {
        for (int job = 1; job <= 11; job++) {
            List<SkillDataRegistry.Skill> rows = reg.ofJob(job);
            for (int slot = 0; slot < 20; slot++) {
                SkillDataRegistry.Skill bySlot = reg.bySlot(job, slot);
                assertEquals(slot, bySlot.slotInJob(), "ofJob 的次序就是槽序（面板序）");
                assertEquals(job, bySlot.classId());
                assertEquals(slot / 4 + 1, bySlot.tier(), "槽序 → 转职档");
                assertEquals(slot % 4 + 1, bySlot.slotInTier(), "槽序 → 档内槽");
                // id 的三段与槽序同构（0x<job><tier><slot>）
                assertEquals((job << 16) | ((slot / 4 + 1) << 8) | (slot % 4 + 1), bySlot.skillId());
                assertEquals(bySlot.skillId(), rows.get(slot).skillId());
                assertSame(bySlot, reg.byId(bySlot.skillId()), "两条查询口回到同一行对象");
            }
        }
        // 点名一条（评审稿 §5 第 2 步的验收判据）
        assertEquals(SkillIds.PIKE_WIND.id(), reg.bySlot(PIKEMAN, 0).skillId());
    }

    @Test
    void 行对象暴露的字段_钉住一条() {
        SkillDataRegistry.Skill s = reg.byId(0x040101);
        assertEquals(0x040101, s.skillId());
        assertEquals("0x040101", s.skillIdHex(), "键里那段是小写 hex（生成物那列字母是大写）");
        assertEquals(PIKEMAN, s.classId());
        assertEquals(0, s.slotInJob());
        assertEquals(1, s.tier());
        assertEquals(1, s.slotInTier());
        assertEquals("SKILL_PIKE_WIND", s.macro());
        assertEquals("PIKE_WIND", s.constName());
        assertEquals("tp10 p_wind.bmp", s.iconFile());
        assertEquals("Pike Wind", s.name());
        assertEquals(10, s.reqLv());
        assertEquals("RIGHT", s.useCode());
        assertEquals("pikeman", s.classDir());
    }

    @Test
    void 枚举常量与技能表逐行对上() {
        // 键的白名单来自 SkillIds、判据来自 skill-tables.json ⇒ 两侧必须是同一套 220 个
        Set<Integer> fromEnum = new LinkedHashSet<>();
        for (SkillIds c : SkillIds.values()) {
            fromEnum.add(c.id());
        }
        assertEquals(tableIds(), fromEnum, "SkillIds.java 与 skill-tables.json 不是同一套 id");

        // 常量名与行里的 constName 同名，且 id 的三段与行一致（改名只在生成物侧发生）
        for (SkillIds c : SkillIds.values()) {
            SkillDataRegistry.Skill s = reg.byId(c.id());
            assertEquals(c.name(), s.constName(), c.name() + " 的常量名");
            assertEquals(c.job(), s.classId());
            assertEquals(c.tier(), s.tier());
            assertEquals(c.slotInTier(), s.slotInTier());
        }
    }

    @Test
    void 有宏的行与无宏的行() {
        int withMacro = 0;
        Set<String> macros = new LinkedHashSet<>();
        for (int job = 1; job <= 11; job++) {
            for (SkillDataRegistry.Skill s : reg.ofJob(job)) {
                if (s.macro() != null) {
                    withMacro++;
                    assertTrue(macros.add(s.macro()), "宏名重复：" + s.macro());
                }
            }
        }
        assertEquals(160, withMacro, "10 职业 × 16 槽有源码宏；5 转 40 + 格斗家整 20 没有");
        assertEquals(160, macros.size());
        assertEquals(0x040101, reg.byMacro("SKILL_PIKE_WIND").skillId());

        // 无宏的行：macro() 是 null，拿它的常量名当宏名查是"查不到"（抛），不是"返回 null"
        SkillDataRegistry.Skill lowkick = reg.byId(0x0B0101);
        assertNull(lowkick.macro(), "格斗家整 20 行没有宏");
        assertThrows(IllegalStateException.class, () -> reg.byMacro("LOWKICK"));
        assertThrows(IllegalStateException.class, () -> reg.byMacro("SKILL_NOPE"));
    }

    @Test
    void 取不到的身份一律抛() {
        // 槽 33（slotInTier 只有 1..4）
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> reg.byId(0x040521));
        assertTrue(e.getMessage().contains("0x40521"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> reg.byId(0));
        assertThrows(IllegalStateException.class, () -> reg.byId(-1));
        assertThrows(IllegalStateException.class, () -> reg.bySlot(PIKEMAN, 20));
        assertThrows(IllegalStateException.class, () -> reg.bySlot(PIKEMAN, -1));
        assertThrows(IllegalStateException.class, () -> reg.bySlot(12, 0));
        assertThrows(IllegalStateException.class, () -> reg.ofJob(12));

        // 只判存在性的那两把不许抛（网络入口先挡垃圾用）
        assertFalse(reg.hasId(0x040521));
        assertFalse(reg.hasId(0));
        assertFalse(reg.hasJob(12));
        assertTrue(reg.hasId(SkillIds.PIKE_WIND.id()));
        assertTrue(reg.hasJob(PIKEMAN));
        assertTrue(reg.hasJob(11), "格斗家现在也在表里（60 个无源码技能之一）");
    }

    /* ─────────────── 载入校验：改坏一份副本，必须抛且点出坏在哪 ─────────────── */

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

    /** 生成物的**副本**（每次现读，改它不影响别的用例）。 */
    private static ObjectNode generatedCopy() throws IOException {
        try (InputStream in = SkillDataRegistry.class.getResourceAsStream(SkillDataRegistry.RESOURCE)) {
            assertNotNull(in, "classpath 上有 " + SkillDataRegistry.RESOURCE);
            return (ObjectNode) TEST_MAPPER.readTree(in);
        }
    }

    private static ObjectNode row(JsonNode root, int i) {
        return (ObjectNode) root.get("skills").get(i);
    }

    private static void loadExpectFail(JsonNode root, String expectInMessage) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SkillDataRegistry().loadFrom(root));
        assertTrue(e.getMessage().contains(expectInMessage),
                "消息里应点出坏在哪（含 '" + expectInMessage + "'）：" + e.getMessage());
    }

    /** 把第 i 行整套身份改成「job 职业的第 slot 槽」（七个字段一起改，免得先撞"行内不自洽"那条）。 */
    private static void setIdentity(ObjectNode root, int i, int job, int slot) {
        ObjectNode r = row(root, i);
        int tier = slot / 4 + 1;
        int slotInTier = slot % 4 + 1;
        int id = (job << 16) | (tier << 8) | slotInTier;
        r.put("job", job);
        r.put("classDir", SkillKeys.classDirOfJob(job));
        r.put("slotInJob", slot);
        r.put("tier", tier);
        r.put("slotInTier", slotInTier);
        r.put("skillId", id);
        r.put("skillIdHex", String.format("0x%06X", id));
    }

    @Test
    void 少一行技能就抛() throws IOException {
        ObjectNode root = generatedCopy();
        ((ArrayNode) root.get("skills")).remove(219);
        loadExpectFail(root, "220");
    }

    @Test
    void 计数与行数不符就抛() throws IOException {
        ObjectNode root = generatedCopy();
        ((ObjectNode) root.get("counts")).put("skills", 219);
        loadExpectFail(root, "counts.skills");
    }

    @Test
    void 槽序不等于职业内行序就抛() throws IOException {
        ObjectNode root = generatedCopy();
        setIdentity(root, 1, 1, 3);   // 第 2 行自称"3 号槽"（行内自洽，但与行序不符）
        loadExpectFail(root, "面板序");
    }

    @Test
    void id与段位不符就抛() throws IOException {
        ObjectNode root = generatedCopy();
        row(root, 1).put("skillId", 0x010103);   // id 改了但 tier/slotInTier 没改
        loadExpectFail(root, "skillId 期望");
    }

    @Test
    void hex与id不同值就抛() throws IOException {
        ObjectNode root = generatedCopy();
        row(root, 1).put("skillIdHex", "0x040103");
        loadExpectFail(root, "不是同一个数");

        ObjectNode shortHex = generatedCopy();
        row(shortHex, 1).put("skillIdHex", "0x40102");   // 少一位（0x040102 写成了 7 字符）
        loadExpectFail(shortHex, "6 位 hex");
    }

    @Test
    void 职业段超范围就抛() throws IOException {
        ObjectNode root = generatedCopy();
        row(root, 0).put("job", 12);
        loadExpectFail(root, "job 期望");
    }

    @Test
    void 档内槽超范围就抛() throws IOException {
        ObjectNode root = generatedCopy();
        row(root, 0).put("slotInTier", 5);
        loadExpectFail(root, "slotInTier 期望");
    }

    @Test
    void classDir与职业号不同套就抛() throws IOException {
        ObjectNode root = generatedCopy();
        row(root, 0).put("classDir", "mecha");
        loadExpectFail(root, "classDir 期望");
    }

    @Test
    void 宏名不在macros段就抛() throws IOException {
        ObjectNode root = generatedCopy();
        row(root, 0).put("macro", "SKILL_TYPO");
        loadExpectFail(root, "不在 macros 段里");
    }

    /** id 重复：把某一行的整套身份改成另一行的（其余字段无所谓 —— 唯一性检查先于其它跨行检查）。 */
    @Test
    void id重复就抛() throws IOException {
        ObjectNode root = generatedCopy();
        ObjectNode src = row(root, 0);      // fighter 一转一槽
        ObjectNode dst = row(root, 20);     // mecha 一转一槽 → 改成 fighter 那一格
        for (String f : List.of("job", "slotInJob", "tier", "slotInTier", "skillId", "skillIdHex", "classDir")) {
            dst.set(f, src.get(f));
        }
        loadExpectFail(root, "重复");
    }
}
