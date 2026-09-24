package org.jpstale.common.service.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.props.SkillKeys;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 技能静态数据：启动时从 classpath 载入 `skilldata/skill-tables.json`，**校验后**提供只读查询。
 *
 * <h3>数据从哪来</h3>
 * 生成物由**客户端**仓库的 `scripts/extract-skill-tables.ts` 从原版源码机械抽取
 * （`.refsrc/tree` 的 `sinbaram/sinSkill_Info.cpp` 参数表 + `sinbaram/sinSkill.h` 技能宏 +
 * `Language/{Brazil,English}/*_sinSkill_Info.h` 技能定义），**同一份字节**同时写到客户端
 * `src/game/data/skill-tables.generated.json` 与本模块的 `resources/skilldata/skill-tables.json`。
 * 两端 `sourceHash` 相同即"同代"；本类不重算哈希（源码不在服务端），只校验它非空并打日志。
 *
 * <h3>数据面</h3>
 * <ul>
 *   <li><b>skills</b> —— **我方技能身份表**，220 行（11 职业 × 20 槽）：数字 `skillId` + 面板位置 + 图标名
 *       + 显示名 + 需求等级。这一段的键是 {@link Skill#skillId()}（协议、props 键、判据都用它），
 *       **不是** `macro`（60 行无宏）也不是数组下标（那只是行序）。</li>
 *   <li><b>arrays</b> —— 每技能每级的参数表，共 491 张（451 `int[10]` + 33 `int[10][2]` + 7 `float[10]`）。
 *       <b>下标 = 技能等级 − 1</b>（源码用法 `Table[sinSkill.UseSkill[i].Point - 1]`）。</li>
 *   <li><b>macros</b> —— `sinSkill.h` 的技能宏 202 条，值为 `GROUP_x | CHANGE_JOBn | SKILL_m` 的按位或。
 *       ⚠ `SKILL_m` 的值**不是** 1..17 连续（`SKILL_10 = 0x10 = 16`），故本类保留 `tier` / `slotInTier`
 *       两个**解析后的语义**字段，调用方不要自己拆位。</li>
 *   <li><b>definitions</b> —— 技能定义（Brazil 198 条 / English 151 条），按位置原样存成 22 项 tuple
 *       （字段顺序 = `sinSkill.h:350-365` 的 `sSKILL_INFO`）：[0] 名 · [1] 说明 · [2] RequireLevel ·
 *       [3..4] UseStamina[2] · [5..6] RequireMastery[2] · [7..9] Element[3] · [10..17] UseWeaponCode[8] ·
 *       [18] FuncPointer · [19] CODE · [20] USECODE · [21] UseMana 表名。本类只把**用到的槽**命名，
 *       其余原样留在 tuple 里由使用方解释（不做全字段映射，避免"字段名骗人"）。</li>
 * </ul>
 *
 * <h3>纪律（AGENTS #12：不许静默兜底）</h3>
 * 缺文件 / 缺键 / 计数不符 / 取不到的表或技能 ⇒ **抛带清楚消息的异常**（消息写清"哪个键、期望值、实得值"）。
 * 查询**不返回 null**、也**不返回空当正常**：唯一的空列表来自 `tablesOfMacro`（那是"命名惯例没命中"这一
 * **事实**，不是兜底 —— 该技能的参数表可能拼写不同或压根没有）。
 *
 * <p>载入时会 WARN 两类**源码事实**（不是错误，但按等级取值前必须知道）：
 * ① 3 张表源码写短（`Blind_Range` 7/10 等）—— 生成物**不补零**，故这些表的"等级上限"就是实际项数；
 * ② 2 个槽位被两个宏占用（`GROUP_OTHERSKILL/2-1`、`/4-15`，源码如此）—— 按槽取时会报出候选。
 */
@Slf4j
@Component
public class SkillDataRegistry {

    /** 生成物在 classpath 上的位置（随 common-service jar 发布到两个 app）。 */
    public static final String RESOURCE = "/skilldata/skill-tables.json";

    /** 生成物自带的计数（与生成器的 `counts` 逐项对应）—— 不符即抛，说明载到的不是这份产物。 */
    private static final int EXPECT_ARRAYS_TOTAL = 491;
    private static final int EXPECT_MACROS = 202;
    private static final int EXPECT_DEFS_BRAZIL = 198;
    private static final int EXPECT_DEFS_ENGLISH = 151;
    /** `skills` 段的行数 = 11 职业 × 20 槽（面板格子）。 */
    private static final int EXPECT_SKILLS = 220;

    /** 每职业的槽数（面板格子数）与每转职档的槽数；`slotInJob` 与 `tier/slotInTier` 的换算靠这两个。 */
    private static final int SLOTS_PER_JOB = 20;
    private static final int SLOTS_PER_TIER = 4;
    /** `skills` 段的职业号与转职档取值范围（1..JOBS / 1..TIERS）。 */
    private static final int JOBS = 11;
    private static final int TIERS = 5;
    /** `skillId` 的段长（每段一字节）。 */
    private static final int ID_SEGMENT_BITS = 8;
    /** `skillIdHex` = `0x` + 6 位 hex。 */
    private static final int HEX_TEXT_LEN = 2 + 6;

    /** `sSKILL_INFO` 展平后的项数（`sinSkill.h:350-365`；末尾 `SkillNum` 一律缺省 ⇒ 0..21）。 */
    private static final int TUPLE_LEN = 22;
    /** 本类**用到**的 tuple 槽位（其余不解释；见类注释）。 */
    private static final int AT_NAME = 0;
    private static final int AT_DOC = 1;
    private static final int AT_REQUIRE_LEVEL = 2;
    private static final int AT_USE_STAMINA = 3;
    private static final int AT_REQUIRE_MASTERY = 5;
    private static final int AT_ELEMENT = 7;
    private static final int AT_USE_WEAPON_CODE = 10;
    private static final int AT_CODE = 19;
    private static final int AT_USE_CODE = 20;
    private static final int AT_USE_MANA = 21;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 职业号 → 源码技能组 `GROUP_*`（下标 = 职业号，0 位占位）。
     * ⚠ **拼写不统一**（`mecha`→`MECHANICIAN`、`assassin`→`ASSASSINE`），故只能显式列出、不能由目录名推。
     */
    private static final String[] GROUP_OF_JOB = {
            null,
            "GROUP_FIGHTER", "GROUP_MECHANICIAN", "GROUP_ARCHER", "GROUP_PIKEMAN", "GROUP_ATALANTA",
            "GROUP_KNIGHT", "GROUP_MAGICIAN", "GROUP_PRIESTESS", "GROUP_ASSASSINE", "GROUP_SHAMAN",
            null,   // 11（martial）：生成物里没有它的组，见 groupOfJob
    };

    /** 生成物自带的计数（校验通过后原样暴露，便于自检与日志）。 */
    public record Counts(int arraysTotal, int macros, int defsBrazil, int defsEnglish) {
    }

    /**
     * 一条技能宏（`sinSkill.h` 的 `#define`）。
     *
     * @param macro      宏名，如 `SKILL_RAVING`
     * @param group      职业/类别组，如 `GROUP_FIGHTER`
     * @param tier       转职档 1..4（= `CHANGE_JOBn` 的 n）
     * @param slotInTier 槽位 = `SKILL_m` 的 **m**（10 个职业组恒 1..4；`GROUP_OTHERSKILL` 用到 5..17）。
     *                   ⚠ **不是** `SKILL_m` 的值：`SKILL_10 = 0x10 = 16`（见 {@link #value()}）
     * @param value      宏值 = `GROUP | CHANGE_JOB | SKILL`（按位或，客户端与服务端同值）
     * @param src        出处（`sinbaram/sinSkill.h:行号`）
     */
    public record SkillMacro(String macro, String group, int tier, int slotInTier, int value, String src) {
    }

    /**
     * 一项技能定义（Brazil 语言的 `sSKILL_INFO`）。
     *
     * @param code            [19] CODE（对职业技能就是宏名，如 `SKILL_RAVING`）
     * @param name            [0] 技能名（该语言文本）
     * @param doc             [1] 技能说明（该语言文本；可能为空串）
     * @param requireLevel    [2] 需求等级
     * @param useStamina      [3..4] `UseStamina[2]` = {基础值, 每级增量}
     * @param requireMastery  [5..6] `RequireMastery[2]` = {基础要求, 每级增量}（0 表示不要求）
     * @param element         [7..9] `Element[3]`（元素属性三元组）
     * @param useWeaponCode   [10..17] `UseWeaponCode[8]` 的**裸 token 原样**：武器族名（如 `sinWA1`）、
     *                        字面量 `0`（源码写了"无"）、或 `null`（该槽源码没写）
     * @param useCode         [20] USECODE（可绑拳位 `SIN_SKILL_USE_*` 的位掩码）
     * @param useManaTable    [21] 该技能 MP 消耗的表名（`null` = 源码写的是 0，即无此表）
     * @param src             出处（如 `Language/Brazil/b_sinSkill_Info.h:106`）
     */
    public record SkillDefinition(String code, String name, String doc, int requireLevel, int[] useStamina,
                                  int[] requireMastery, int[] element, String[] useWeaponCode, String useCode,
                                  String useManaTable, String src) {

        /** 源码写"无"的槽（字面量 0）与没写的槽都不算武器族 ⇒ 这里只返回真正的族名。 */
        public List<String> weaponCodes() {
            List<String> out = new ArrayList<>(useWeaponCode.length);
            for (String w : useWeaponCode) {
                if (w != null && !"0".equals(w)) {
                    out.add(w);
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * 一个"槽位上的技能"：宏 +（Brazil）定义。
     *
     * @param group      如 `GROUP_FIGHTER`
     * @param tier       转职档 1..4
     * @param slotInTier 槽位（职业组 1..4；见 {@link SkillMacro#slotInTier()})
     * @param macro      该槽的宏名
     * @param value      宏值（同 {@link SkillMacro#value()}）
     * @param definition 该宏的（Brazil）定义；**非 null** —— 宏没有定义时 {@link #skill} 直接抛，不给半条
     */
    public record SkillSlot(String group, int tier, int slotInTier, String macro, int value,
                            SkillDefinition definition) {
    }

    /**
     * 一行技能（生成物的 `skills` 段）—— **我方技能身份**：协议、props 键、服务端判据都用 {@link #skillId()}。
     *
     * @param skillId    数字身份 = `0x<job><tier><slot>`（每段一字节、十进制值、hex 里直读）
     * @param classId    职业号 1..11（与 `Player.job` 同一套）
     * @param slotInJob  本职业内槽序 0..19（**0 基**；= 面板格子 = 数组行序）
     * @param tier       转职档 1..5
     * @param slotInTier 档内槽 1..4
     * @param macro      源码技能宏名；60 行没有宏（5 转 40 + 格斗家整 20）⇒ **null**
     * @param iconFile   图标文件名（客户端表现链的桥，键集由生成物校验）
     * @param name       显示名（不进键、不当判据）
     * @param constName  枚举常量名（= `SkillIds` 的常量名，只作可读性）
     * @param reqLv      需求等级（学习门槛：`reqLv + 当前等级×2 <= 角色等级`）
     * @param useCode    可绑拳位（源码 `USECODE` 的裸 token，如 `ALL`/`RIGHT`/`NOT`）
     * @param classDir   职业目录名（与 `SkillKeys.classDirOfJob` 同套，只作日志/对账）
     * @param element0   `Element[0]`（`sinSkill.h` 的 `sSKILL_INFO.Element[3]` 首项）。原版**只当布尔**读它，
     *                   且有两处语义：`sinSkill.cpp:2064` 熟练度恒满（⇒ CD 最短）、`:839` 面板画粉色 gage；
     *                   取值口径与逐值 provenance 见生成物的 `elementNote` / `element0Src`
     */
    public record Skill(int skillId, int classId, int slotInJob, int tier, int slotInTier, String macro,
                        String iconFile, String name, String constName, int reqLv, String useCode,
                        String classDir, int element0) {

        /** `0x` + 6 位小写 hex（**props 键用的就是它**；生成物那列是大写，别照抄）。 */
        public String skillIdHex() {
            return "0x" + String.format(Locale.ROOT, "%06x", skillId);
        }
    }

    /** 一张参数表（内部形态：1 维或 2 维二选一）。 */
    private record ParamTable(String name, String type, int[] dims, double[] values1d, double[][] values2d,
                              String src) {
    }

    private volatile Counts counts;
    private volatile String sourceHash;
    private volatile Map<String, ParamTable> tables = Map.of();
    private volatile Map<String, SkillMacro> macrosByName = Map.of();
    private volatile Map<String, List<SkillMacro>> macrosBySlot = Map.of();
    private volatile Map<String, SkillDefinition> definitions = Map.of();
    private volatile Map<Integer, Skill> skillsById = Map.of();
    private volatile Map<Integer, Skill> skillsByJobSlot = Map.of();
    private volatile Map<Integer, List<Skill>> skillsByJob = Map.of();
    private volatile Map<String, Skill> skillsByMacro = Map.of();

    /**
     * 载入 + 校验（Spring 启动时调用；测试里直接 `new SkillDataRegistry().load()` 即可）。
     *
     * 校验失败**直接抛**（让服务起不来）—— 技能数值算错比服务起不来更糟：这类数据是"静默错值"的
     * 高发区（对照 AGENTS 多条：#12 静默兜底、#23 维度用反）。
     */
    @PostConstruct
    public void load() {
        loadFrom(readRoot());
    }

    /**
     * 载入 + 校验（入参是已经读进来的 JSON 树）。
     *
     * <p>与 {@link #load()} 分开是为了让校验本身**可受测**：测试改坏一份副本再喂进来，
     * 断言它抛且消息里点出坏在哪条（校验不被测 = 不知道自己有没有用）。
     */
    void loadFrom(JsonNode root) {
        String hash = requiredText(root, "sourceHash", "顶层");
        if (hash.isBlank()) {
            throw fail("sourceHash 是空串 —— 生成物没写完？");
        }
        JsonNode countsNode = requiredObject(root, "counts", "顶层");
        int arraysTotal = requiredCount(countsNode, "arraysTotal", EXPECT_ARRAYS_TOTAL);
        int macroCount = requiredCount(countsNode, "macros", EXPECT_MACROS);
        int defsBrazil = requiredCount(countsNode, "defsBrazil", EXPECT_DEFS_BRAZIL);
        int defsEnglish = requiredCount(countsNode, "defsEnglish", EXPECT_DEFS_ENGLISH);
        int skillCount = requiredCount(countsNode, "skills", EXPECT_SKILLS);

        Map<String, ParamTable> builtTables =
                loadTables(requiredObject(root, "arrays", "顶层"), loadDefects(requiredArray(root, "defects", "顶层")));
        Map<String, SkillMacro> builtMacros = loadMacros(requiredArray(root, "macros", "顶层"));
        Map<String, SkillDefinition> builtDefs =
                loadDefinitions(requiredArray(requiredObject(root, "definitions", "顶层"), "brazil", "definitions"));
        List<Skill> builtSkills = loadSkills(requiredArray(root, "skills", "顶层"), skillCount, builtMacros);

        Map<String, List<SkillMacro>> bySlot = new LinkedHashMap<>();
        List<String> collisions = new ArrayList<>();
        for (SkillMacro m : builtMacros.values()) {
            List<SkillMacro> bucket = bySlot.computeIfAbsent(slotKey(m.group(), m.tier(), m.slotInTier()),
                    k -> new ArrayList<>());
            bucket.add(m);
            if (bucket.size() == 2) {
                // ⚠ 这不是数据错误：源码**本身**在 GROUP_OTHERSKILL 上把两个宏写在了同一槽位
                //   （`SKILLDELAY_ITEM_LIFE` & `SCROLL_INVULNERABILITY` 等）。10 个职业组无此情形。
                //   故只记 WARN 并保留全部候选（取"第一个"是主观选择），`skill()` 遇到多义会明确报出。
                collisions.add(slotKey(m.group(), m.tier(), m.slotInTier()) + " → "
                        + bucket.stream().map(SkillMacro::macro).toList());
            }
        }
        if (!collisions.isEmpty()) {
            log.warn("[Skill] 有 {} 个槽位被多个宏占用（源码如此），按槽取技能时会报出候选：{}",
                    collisions.size(), collisions);
        }

        this.tables = Map.copyOf(builtTables);
        this.macrosByName = Map.copyOf(builtMacros);
        this.macrosBySlot = Map.copyOf(bySlot);
        this.definitions = Map.copyOf(builtDefs);
        this.skillsById = Map.copyOf(indexById(builtSkills));
        this.skillsByJobSlot = Map.copyOf(indexByJobSlot(builtSkills));
        this.skillsByJob = Map.copyOf(indexByJob(builtSkills));
        this.skillsByMacro = Map.copyOf(indexByMacro(builtSkills));
        this.sourceHash = hash;
        this.counts = new Counts(arraysTotal, macroCount, defsBrazil, defsEnglish);

        log.info("技能数据载入：{} 表 / {} 宏 / {} 技能行 / sourceHash={}",
                builtTables.size(), builtMacros.size(), builtSkills.size(), hash);
    }

    /* ─────────────── 查询（全部只读；取不到即抛，不返回 null） ─────────────── */

    public Counts counts() {
        return requireLoaded(this.counts, "counts");
    }

    /** 生成输入（`.refsrc` 四份源码）的哈希，用于两端比对"是否同代"。 */
    public String sourceHash() {
        return requireLoaded(this.sourceHash, "sourceHash");
    }

    /**
     * 职业号 → `GROUP_*`。
     *
     * <p>⚠ 11（martial / 格斗家）**没有组**：那 20 个技能在源码里没有宏（键名尚未定稿）。
     * 这里抛，而不是返回一个空组 —— 空组会静默算成"一个技能都没学、点数全满"。
     */
    public static String groupOfJob(int job) {
        if (job < 1 || job >= GROUP_OF_JOB.length || GROUP_OF_JOB[job] == null) {
            throw new IllegalArgumentException("职业号 " + job + " 没有技能组"
                    + "（1..10；11 = 格斗家的技能没有源码宏，见 SkillDataRegistry）");
        }
        return GROUP_OF_JOB[job];
    }

    /**
     * 该职业有没有技能组。
     *
     * <p>与 {@link #groupOfJob} 的分工：**存在性判断用它**（不抛，给网络入口用 —— 请求可能来自
     * 一个没有技能树的职业），**取组名仍用 `groupOfJob`**（缺就抛，别把"没有"当"空组"）。
     */
    public static boolean hasGroupOfJob(int job) {
        return job >= 1 && job < GROUP_OF_JOB.length && GROUP_OF_JOB[job] != null;
    }

    /**
     * 该宏的（Brazil）定义；宏不存在、或该宏没有定义条目 ⇒ 抛。
     *
     * ⚠ 本方法按定义 tuple 的 **[19] CODE** 索引，**不是**生成物顶层 `definitions[].code` 那个字段
     * —— 后者只在 CODE 以 `SKILL_` 开头时才有值（生成器的口径），照它索引会**漏掉**一批真实存在的定义
     * （如 `SCROLL_INVULNERABILITY`、`ELIXIR_*`、`CLANSKILL_*`：它们有定义、但 CODE 不叫 `SKILL_*`）。
     * 实测：202 个宏里只有 4 个**真的**没有定义（`SKILLDELAY_ITEM_LIFE` / `BUFF_WARMODE` /
     * `ELIXIR_IRA` / `ELIXIR_GLORIA`），其余 198 个一一对上。
     */
    public SkillDefinition definition(String macro) {
        Map<String, SkillDefinition> defs = requireLoaded(this.definitions, "definitions");
        SkillDefinition def = defs.get(macro);
        if (def == null) {
            Map<String, SkillMacro> ms = requireLoaded(this.macrosByName, "macros");
            if (!ms.containsKey(macro)) {
                throw fail("技能宏 '" + macro + "' 不存在（macros 共 " + ms.size() + " 条，没有这个名字）");
            }
            throw fail("技能宏 '" + macro + "' 在 Brazil 定义里没有条目"
                    + "（definitions.brazil 共 " + defs.size() + " 条有 CODE；物品/卷轴/活动类技能属此类）");
        }
        return def;
    }

    /** 宏名 → 宏行；不存在 ⇒ 抛。 */
    public SkillMacro macro(String macro) {
        SkillMacro m = requireLoaded(this.macrosByName, "macros").get(macro);
        if (m == null) {
            throw fail("技能宏 '" + macro + "' 不存在（macros 共 "
                    + requireLoaded(this.macrosByName, "macros").size() + " 条）");
        }
        return m;
    }

    /**
     * 该宏名是否存在（**只判存在性**，供网络入口先挡一手垃圾字符串）。
     *
     * <p>取数据仍走 {@link #macro} / {@link #definition}（缺就抛）—— 这里不返回 null，
     * 也不要拿它当"取不到就跳过"的替代。
     */
    public boolean hasMacro(String macro) {
        return macro != null && requireLoaded(this.macrosByName, "macros").containsKey(macro);
    }

    /**
     * 按 `group + tier + slotInTier` 取"槽位上的技能"。
     *
     * ⚠ 该槽有宏但**没有 Brazil 定义**时同样抛（实测只有 4 个宏如此：`SKILLDELAY_ITEM_LIFE` /
     * `BUFF_WARMODE` / `ELIXIR_IRA` / `ELIXIR_GLORIA`）—— 那不是"查不到"，而是"这条技能只有编号没有定义"，
     * 两者必须能分开看（消息里会写明）。
     */
    public SkillSlot skill(String group, int tier, int slotInTier) {
        List<SkillMacro> at = requireLoaded(this.macrosBySlot, "macros").get(slotKey(group, tier, slotInTier));
        if (at == null || at.isEmpty()) {
            throw fail("槽位 " + slotKey(group, tier, slotInTier) + " 上没有技能宏"
                    + "（宏观值 = GROUP | CHANGE_JOB | SKILL；tier 取 1..4、slotInTier 取 1..17，职业组恒 1..4）");
        }
        if (at.size() > 1) {
            throw fail("槽位 " + slotKey(group, tier, slotInTier) + " 上有 " + at.size() + " 个宏（源码如此）："
                    + at.stream().map(SkillMacro::macro).toList() + " —— 请改用 definition(宏名)");
        }
        SkillMacro m = at.get(0);
        return new SkillSlot(m.group(), m.tier(), m.slotInTier(), m.macro(), m.value(), definition(m.macro()));
    }

    /** 该组内**有 Brazil 定义**的全部槽位，按 (tier, slotInTier) 升序。 */
    public List<SkillSlot> skillsOfGroup(String group) {
        List<SkillMacro> inGroup = new ArrayList<>();
        for (SkillMacro m : requireLoaded(this.macrosByName, "macros").values()) {
            if (m.group().equals(group)) {
                inGroup.add(m);
            }
        }
        if (inGroup.isEmpty()) {
            throw fail("组 '" + group + "' 没有任何技能宏（组名形如 GROUP_FIGHTER）");
        }
        inGroup.sort((a, b) -> a.tier() != b.tier() ? a.tier() - b.tier() : a.slotInTier() - b.slotInTier());
        List<SkillSlot> out = new ArrayList<>(inGroup.size());
        List<String> withoutDef = new ArrayList<>();
        for (SkillMacro m : inGroup) {
            SkillDefinition def = requireLoaded(this.definitions, "definitions").get(m.macro());
            if (def == null) {
                withoutDef.add(m.macro());
                continue;   // 只有编号没有定义（物品/卷轴类）—— 这里不造 null 定义，见类注释
            }
            out.add(new SkillSlot(m.group(), m.tier(), m.slotInTier(), m.macro(), m.value(), def));
        }
        if (!withoutDef.isEmpty() && log.isInfoEnabled()) {
            // 可见而非静默：调用方拿到的是"有定义的技能"，缺定义的名字在这里能查到
            log.info("[Skill] 组 {} 有 {} 个宏没有 Brazil 定义、未列入返回：{}", group, withoutDef.size(), withoutDef);
        }
        return List.copyOf(out);
    }

    /* ─────────────── 技能身份（`skills` 段 = 220 行；这才是"一个技能"的身份） ─────────────── */

    /** 该职业有没有技能行。**只判存在性**（给网络入口挡一个不属于任何职业的 job 号）。 */
    public boolean hasJob(int classId) {
        return requireLoaded(this.skillsByJob, "skills").containsKey(classId);
    }

    /**
     * 这个 id 在不在 skills 表里（**只判存在性**，供网络入口先挡一手垃圾整数）。
     *
     * <p>取数据仍走 {@link #byId}（缺就抛）—— 这里不返回 null，也不要拿它当"取不到就跳过"的替代。
     */
    public boolean hasId(int skillId) {
        return requireLoaded(this.skillsById, "skills").containsKey(skillId);
    }

    /**
     * 按数字身份取技能行；不在表里（或 id 为 0/负数）⇒ 抛。
     *
     * ⚠ 这是"技能存不存在"，不是"该角色能不能学"—— 职业门在 {@code SkillRules.judge}（唯一判定处）。
     */
    public Skill byId(int skillId) {
        Skill s = requireLoaded(this.skillsById, "skills").get(skillId);
        if (s == null) {
            throw fail("技能 id 0x" + Integer.toHexString(skillId) + " 不在 skills 表里（共 "
                    + requireLoaded(this.skillsById, "skills").size() + " 行）");
        }
        return s;
    }

    /** 按（职业号, 本职业内槽序 0..19）取技能行；该格子没有技能 ⇒ 抛。 */
    public Skill bySlot(int classId, int slotInJob) {
        if (slotInJob < 0 || slotInJob >= SLOTS_PER_JOB) {
            throw fail("槽序 " + slotInJob + " 超范围（0.." + (SLOTS_PER_JOB - 1) + "）");
        }
        Skill s = requireLoaded(this.skillsByJobSlot, "skills").get(jobSlotKey(classId, slotInJob));
        if (s == null) {
            throw fail("职业 " + classId + " 的槽 " + slotInJob + " 上没有技能行"
                    + "（每职业 " + SLOTS_PER_JOB + " 槽，职业号 1..11）");
        }
        return s;
    }

    /** 该职业的 20 行，按 `slotInJob` 升序（= 面板序）；该职业没有技能行 ⇒ 抛。 */
    public List<Skill> ofJob(int classId) {
        List<Skill> rows = requireLoaded(this.skillsByJob, "skills").get(classId);
        if (rows == null) {
            throw fail("职业 " + classId + " 在 skills 表里没有技能行（职业号 1..11）");
        }
        return rows;
    }

    /**
     * 按源码宏名取技能行；没有这个宏 ⇒ 抛。
     *
     * <p>60 行（5 转 40 + 格斗家整 20）**没有宏**，用它们的键一定落到"没有这个宏"这条分支上
     * —— 宏名只用于对账与日志，**不要拿它当身份**（身份是 {@link Skill#skillId()}）。
     */
    public Skill byMacro(String macro) {
        Skill s = requireLoaded(this.skillsByMacro, "skills").get(macro);
        if (s == null) {
            throw fail("skills 表里没有宏 '" + macro + "' 的行（有宏的行 "
                    + requireLoaded(this.skillsByMacro, "skills").size() + " / "
                    + requireLoaded(this.skillsById, "skills").size() + "）");
        }
        return s;
    }

    /**
     * 参数表 1 维取值（下标 = 技能等级 − 1）；取不到或维度不符 ⇒ 抛。
     *
     * ⚠ 返回长度**可能小于 `dims[0]`**：源码自己把个别表写短了（如 `Blind_Range` 写了 7/10，C 编译时补 0）
     * ——生成物**不补零**（补 0 是编造值），故这些表在"声明长度以内、实际项数以外"的等级上**没有值**。
     * 要按等级取值的调用方应先看 {@link #tableLength(String)}（载入时也会 WARN 列出这几张）。
     */
    public double[] table1d(String name) {
        ParamTable t = table(name);
        if (t.values1d() == null) {
            throw fail("参数表 '" + name + "' 是 " + dimsStr(t.dims()) + " 维表，不能用 table1d 取（应用 table2d）");
        }
        return t.values1d().clone();
    }

    /** 参数表 2 维取值（`[等级][列]`）；取不到或维度不符 ⇒ 抛。 */
    public double[][] table2d(String name) {
        ParamTable t = table(name);
        if (t.values2d() == null) {
            throw fail("参数表 '" + name + "' 是 " + dimsStr(t.dims()) + " 维表，不能用 table2d 取（应用 table1d）");
        }
        // 深拷贝：`double[][]` 的 clone 只复制外层，调用方改一行会污染注册表里的数据
        double[][] src = t.values2d();
        double[][] copy = new double[src.length][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i].clone();
        }
        return copy;
    }

    public boolean hasTable(String name) {
        return requireLoaded(this.tables, "arrays").containsKey(name);
    }

    /**
     * 该表**实际有**多少项（1 维表 = 项数；2 维表 = 行数）。
     * 绝大多数表 = 声明长度（10）；源码写短的少数表会小于它，见 {@link #table1d(String)}。
     */
    public int tableLength(String name) {
        ParamTable t = table(name);
        return t.values1d() != null ? t.values1d().length : t.values2d().length;
    }

    /** 该表第 i 行的长度（只对 2 维表有意义）；1 维表 ⇒ 抛。 */
    public int rowLength(String name, int row) {
        ParamTable t = table(name);
        if (t.values2d() == null) {
            throw fail("参数表 '" + name + "' 是 " + dimsStr(t.dims()) + " 维表，没有行");
        }
        if (row < 0 || row >= t.values2d().length) {
            throw fail("参数表 '" + name + "' 只有 " + t.values2d().length + " 行，取不到第 " + row + " 行");
        }
        return t.values2d()[row].length;
    }

    /** 参数表出处（`sinbaram/sinSkill_Info.cpp:行号`）—— 便于把数值倒查回源码。 */
    public String tableSource(String name) {
        return table(name).src();
    }

    /**
     * 该技能（按 macro 名）名下**按名字惯例**能命中的参数表名列表。
     *
     * 惯例 = 源码的 `技能名_用途`（`SKILL_RAVING` → `Raving_*`）。⚠ 惯例**只对一部分技能成立**：
     * `SKILL_TRIPLE_IMPACT` 的表实际叫 `T_Impact_*`、`SKILL_RAGE_OF_ZECRAM` 叫 `R_Zecram_*`（源码缩写），
     * 纯被动（`SKILL_FIRE_ATTRIBUTE` 等）压根没有表 —— 命中 0 张是**常态**，那时直接用
     * `table1d(源码里的真实表名)`；定义里**显式**写着表名的只有 `[21] UseMana`（见 {@link SkillDefinition#useManaTable()}）。
     * 返回空列表是"惯例没命中"的**事实**，不是兜底。
     */
    public List<String> tablesOfMacro(String macro) {
        definition(macro);   // 宏/定义不存在 ⇒ 抛（不静默空）
        String suffix = macro.startsWith("SKILL_") ? macro.substring("SKILL_".length()) : macro;
        String prefix = suffix.toLowerCase(Locale.ROOT) + "_";
        List<String> hit = new ArrayList<>();
        for (String name : requireLoaded(this.tables, "arrays").keySet()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                hit.add(name);
            }
        }
        hit.sort(String::compareTo);
        return List.copyOf(hit);
    }

    /** 全部参数表名（升序）—— 排查"表名到底叫什么"用。 */
    public List<String> tableNames() {
        List<String> names = new ArrayList<>(requireLoaded(this.tables, "arrays").keySet());
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private ParamTable table(String name) {
        if (name == null) {
            throw fail("参数表名不能为 null（表名形如 Raving_Damage）");
        }
        ParamTable t = requireLoaded(this.tables, "arrays").get(name);
        if (t == null) {
            throw fail("参数表 '" + name + "' 不存在（arrays 共 "
                    + requireLoaded(this.tables, "arrays").size() + " 张；表名区分大小写）");
        }
        return t;
    }

    /* ─────────────── 载入各段 ─────────────── */

    private JsonNode readRoot() {
        try (InputStream in = SkillDataRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw fail("缺少 classpath 资源 " + RESOURCE
                        + "（由客户端仓库 scripts/extract-skill-tables.ts 生成，"
                        + "应在 common-service/src/main/resources/skilldata/ 下随产物发布）");
            }
            JsonNode root = MAPPER.readTree(in);
            if (root == null || !root.isObject()) {
                throw fail("顶层不是 JSON 对象（实得 " + describe(root) + "）");
            }
            return root;
        } catch (IOException e) {
            throw fail("读取 " + RESOURCE + " 失败：" + e);
        }
    }

    private Map<String, ParamTable> loadTables(JsonNode arrays, Map<String, int[]> defects) {
        Map<String, ParamTable> out = new LinkedHashMap<>();
        List<String> shortOnes = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = arrays.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode t = e.getValue();
            if (!t.isObject()) {
                throw fail("arrays." + name + " 不是对象（实得 " + describe(t) + "）");
            }
            JsonNode dimsNode = requiredArray(t, "dims", "arrays." + name);
            String type = requiredText(t, "type", "arrays." + name);
            String src = requiredText(t, "src", "arrays." + name);
            JsonNode values = requiredArray(t, "values", "arrays." + name);
            int[] dims = new int[dimsNode.size()];
            if (dims.length != 1 && dims.length != 2) {
                throw fail("arrays." + name + ".dims 期望 1 或 2 维，实得 " + dims.length + " 维");
            }
            for (int i = 0; i < dims.length; i++) {
                dims[i] = intOf(dimsNode.get(i), "arrays." + name + ".dims[" + i + "]");
            }
            // ⚠ 源码**自己写短**的初始化列表（C 编译时会补 0）在生成物里**没有被补零**
            //   （生成器口径：原样记录 + 把事实列进 `defects`）⇒ 这里允许"短"，但**必须在 defects 里声明**：
            //   没声明的短表一律抛（那是产品坏了，不是源码的锅）。
            checkLength(name, dims[0], values.size(), defects, src);
            if (values.size() != dims[0]) {
                shortOnes.add(name + " " + values.size() + "/" + dims[0] + "（" + src + "）");
            }
            if (dims.length == 1) {
                double[] v = new double[values.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = numOf(values.get(i), "arrays." + name + ".values[" + i + "]");
                }
                out.put(name, new ParamTable(name, type, dims, v, null, src));
            } else {
                double[][] v = new double[values.size()][];
                for (int i = 0; i < values.size(); i++) {
                    JsonNode row = values.get(i);
                    if (!row.isArray()) {
                        throw fail("arrays." + name + ".values[" + i + "] 期望数组，实得 " + describe(row));
                    }
                    checkLength(name + "[" + i + "]", dims[1], row.size(), defects, src);
                    v[i] = new double[row.size()];
                    for (int j = 0; j < row.size(); j++) {
                        v[i][j] = numOf(row.get(j), "arrays." + name + ".values[" + i + "][" + j + "]");
                    }
                }
                out.put(name, new ParamTable(name, type, dims, null, v, src));
            }
        }
        if (out.isEmpty()) {
            throw fail("arrays 是空的");
        }
        if (!shortOnes.isEmpty()) {
            // **可见而非静默**：这些表的"等级上限"比 10 小，按等级取值时必须知道这件事
            log.warn("[Skill] {} 张表在源码里写短了（生成物未补零，取值时长度即上限）：{}",
                    shortOnes.size(), shortOnes);
        }
        return out;
    }

    /** `defects` 段：`表名（或 `表名[行号]`）→ {declared, actual}`，即源码自己写短的初始化列表。 */
    private Map<String, int[]> loadDefects(JsonNode defects) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (JsonNode d : defects) {
            if (!d.isObject()) {
                throw fail("defects 里有非对象元素：" + describe(d));
            }
            String name = requiredText(d, "name", "defects[]");
            int declared = intOf(requiredNode(d, "declared", "defects[" + name + "]"), "defects[" + name + "].declared");
            int actual = intOf(requiredNode(d, "actual", "defects[" + name + "]"), "defects[" + name + "].actual");
            if (out.put(name, new int[]{declared, actual}) != null) {
                throw fail("defects 里重复声明：" + name);
            }
        }
        return out;
    }

    /** 长度校验：多写 ⇒ 抛；写短 ⇒ 必须在 `defects` 里按 `declared/actual` 逐项声明，否则抛。 */
    private static void checkLength(String name, int declared, int actual, Map<String, int[]> defects, String src) {
        if (actual > declared) {
            throw fail("arrays." + name + " 的项数（" + actual + "）超过声明长度 " + declared
                    + "（" + src + "）—— 抽取器或数据有问题");
        }
        if (actual == declared) {
            return;
        }
        int[] defect = defects.get(name);
        if (defect == null) {
            throw fail("arrays." + name + " 的项数 " + actual + " 少于声明长度 " + declared + "（" + src
                    + "），但 defects 里**没有**声明这一条 —— 生成物与 defects 不自洽");
        }
        if (defect[0] != declared || defect[1] != actual) {
            throw fail("arrays." + name + " 的项数 " + actual + "/" + declared + " 与 defects 里的声明 "
                    + defect[1] + "/" + defect[0] + " 不一致");
        }
    }

    private Map<String, SkillMacro> loadMacros(JsonNode macros) {
        Map<String, SkillMacro> out = new LinkedHashMap<>();
        for (JsonNode m : macros) {
            if (!m.isObject()) {
                throw fail("macros 里有非对象元素：" + describe(m));
            }
            String macro = requiredText(m, "macro", "macros[]");
            String group = requiredText(m, "group", "macros[" + macro + "]");
            int tier = intOf(requiredNode(m, "tier", "macros[" + macro + "]"), "macros[" + macro + "].tier");
            int slotInTier = intOf(requiredNode(m, "slotInTier", "macros[" + macro + "]"),
                    "macros[" + macro + "].slotInTier");
            int value = intOf(requiredNode(m, "value", "macros[" + macro + "]"), "macros[" + macro + "].value");
            String src = requiredText(m, "src", "macros[" + macro + "]");
            if (tier < 1 || tier > 4) {
                throw fail("macros[" + macro + "].tier 期望 1..4，实得 " + tier);
            }
            if (slotInTier < 1 || slotInTier > 17) {
                // sinSkill.h 只定义到 SKILL_17（`#define SKILL_17 0x00000017`）
                throw fail("macros[" + macro + "].slotInTier 期望 1..17（SKILL_1..SKILL_17），实得 " + slotInTier);
            }
            SkillMacro prev = out.put(macro, new SkillMacro(macro, group, tier, slotInTier, value, src));
            if (prev != null) {
                throw fail("宏名重复：" + macro);
            }
        }
        if (out.isEmpty()) {
            throw fail("macros 是空的");
        }
        return out;
    }

    private Map<String, SkillDefinition> loadDefinitions(JsonNode brazil) {
        Map<String, SkillDefinition> out = new LinkedHashMap<>();
        for (JsonNode d : brazil) {
            if (!d.isObject()) {
                throw fail("definitions.brazil 里有非对象元素：" + describe(d));
            }
            String src = requiredText(d, "src", "definitions.brazil[]");
            JsonNode tuple = requiredArray(d, "tuple", "definitions.brazil[" + src + "]");
            if (tuple.size() != TUPLE_LEN) {
                throw fail("definitions.brazil[" + src + "].tuple 期望 " + TUPLE_LEN
                        + " 项（sSKILL_INFO 字段数），实得 " + tuple.size());
            }
            String code = textOrNull(tuple.get(AT_CODE));
            if (code == null) {
                continue;   // 没有 CODE 的定义（文件尾部的 others 段）无法按宏名索引
            }
            String name = textOrNull(tuple.get(AT_NAME));
            String doc = textOrNull(tuple.get(AT_DOC));
            int requireLevel = intOf(tuple.get(AT_REQUIRE_LEVEL),
                    "definitions.brazil[" + src + "].tuple[" + AT_REQUIRE_LEVEL + "]");
            int[] useStamina = pair(tuple, AT_USE_STAMINA, src);
            int[] requireMastery = pair(tuple, AT_REQUIRE_MASTERY, src);
            int[] element = new int[3];
            for (int i = 0; i < 3; i++) {
                element[i] = intOf(tuple.get(AT_ELEMENT + i),
                        "definitions.brazil[" + src + "].tuple[" + (AT_ELEMENT + i) + "]");
            }
            String[] weapons = new String[8];
            for (int i = 0; i < 8; i++) {
                weapons[i] = textOrNull(tuple.get(AT_USE_WEAPON_CODE + i));
            }
            SkillDefinition prev = out.put(code, new SkillDefinition(code, name, doc, requireLevel, useStamina,
                    requireMastery, element, weapons, textOrNull(tuple.get(AT_USE_CODE)),
                    textOrNull(tuple.get(AT_USE_MANA)), src));
            if (prev != null) {
                throw fail("definitions.brazil 里 CODE 重复：" + code + "（" + prev.src() + " 与 " + src + "）");
            }
        }
        return out;
    }

    /**
     * 定义 tuple 里的 `int[2]` 小数组（UseStamina / RequireMastery）。
     * ⚠ 这里**刻意只读两个槽**（源码就连着写两个），不按"数组"通读 —— 槽位固定才不会被错位读走。
     */
    private int[] pair(JsonNode tuple, int at, String src) {
        int[] v = new int[2];
        for (int i = 0; i < 2; i++) {
            v[i] = intOf(tuple.get(at + i), "definitions.brazil[" + src + "].tuple[" + (at + i) + "]");
        }
        return v;
    }

    /**
     * `skills` 段：220 行技能身份 + 面板位置。逐行校验，任何一条不符**直接抛**
     * （身份表错一格，等级就会写到另一个技能上，而且没有任何症状）。
     *
     * <p>校验：行数 = `counts.skills` · `skillId` 唯一且 = `0x<job><tier><slot>` ·
     * `skillIdHex` 与 `skillId` 同值 · `slotInJob == (tier−1)×4 + slotInTier − 1` ·
     * `slotInJob` 等于该职业内的行序（每职业恰好 20 行）· `classDir` 与职业号同套 ·
     * 有宏的行其宏名必须能在 `macros` 段里找到（宏名只作对账，但写错就成了假日志）。
     */
    private List<Skill> loadSkills(JsonNode skills, int declaredCount, Map<String, SkillMacro> macros) {
        if (skills.size() != declaredCount) {
            throw fail("skills 段有 " + skills.size() + " 行，但 counts.skills 声明 " + declaredCount
                    + " 行（11 职业 × " + SLOTS_PER_JOB + " 槽）");
        }
        List<Skill> out = new ArrayList<>(skills.size());
        Map<Integer, Integer> rowsSeenOfJob = new LinkedHashMap<>();   // 职业 → 已见行数（= 下一行该有的 slotInJob）
        Set<Integer> ids = new LinkedHashSet<>();
        Set<String> macroSeen = new LinkedHashSet<>();
        for (int i = 0; i < skills.size(); i++) {
            JsonNode n = skills.get(i);
            String where = "skills[" + i + "]";
            if (!n.isObject()) {
                throw fail(where + " 期望对象，实得 " + describe(n));
            }
            String constName = requiredText(n, "constName", where);
            String rawHex = requiredText(n, "skillIdHex", where);
            where = where + "（" + constName + " " + rawHex + "）";
            int skillId = intOf(requiredNode(n, "skillId", where), where + ".skillId");
            int classId = intOf(requiredNode(n, "job", where), where + ".job");
            int slotInJob = intOf(requiredNode(n, "slotInJob", where), where + ".slotInJob");
            int tier = intOf(requiredNode(n, "tier", where), where + ".tier");
            int slotInTier = intOf(requiredNode(n, "slotInTier", where), where + ".slotInTier");
            int reqLv = intOf(requiredNode(n, "reqLv", where), where + ".reqLv");
            String macro = textOrNull(n.get("macro"));   // 60 行没有宏（5 转 40 + 格斗家整 20）
            String iconFile = requiredText(n, "iconFile", where);
            String name = requiredText(n, "name", where);
            String useCode = requiredText(n, "useCode", where);
            String classDir = requiredText(n, "classDir", where);

            if (classId < 1 || classId > JOBS) {
                throw fail(where + ".job 期望 1.." + JOBS + "，实得 " + classId);
            }
            if (tier < 1 || tier > TIERS) {
                throw fail(where + ".tier 期望 1.." + TIERS + "，实得 " + tier);
            }
            if (slotInTier < 1 || slotInTier > SLOTS_PER_TIER) {
                throw fail(where + ".slotInTier 期望 1.." + SLOTS_PER_TIER + "，实得 " + slotInTier);
            }
            if (slotInJob < 0 || slotInJob >= SLOTS_PER_JOB) {
                throw fail(where + ".slotInJob 期望 0.." + (SLOTS_PER_JOB - 1) + "，实得 " + slotInJob);
            }
            int expectSlot = (tier - 1) * SLOTS_PER_TIER + (slotInTier - 1);
            if (slotInJob != expectSlot) {
                throw fail(where + ".slotInJob 期望 " + expectSlot + "（= (tier−1)×" + SLOTS_PER_TIER
                        + " + slotInTier−1），实得 " + slotInJob + " —— 面板格子与 id 的两段不同步");
            }
            int expectId = (classId << (2 * ID_SEGMENT_BITS)) | (tier << ID_SEGMENT_BITS) | slotInTier;
            if (skillId != expectId) {
                throw fail(where + ".skillId 期望 " + hex(expectId) + "（= 0x<job><tier><slot> = " + classId
                        + "/" + tier + "/" + slotInTier + "），实得 " + hex(skillId));
            }
            if (!ids.add(skillId)) {
                throw fail(where + ".skillId " + hex(skillId) + " 重复");
            }
            checkHex(rawHex, skillId, where);

            int rowInJob = rowsSeenOfJob.getOrDefault(classId, 0);
            if (slotInJob != rowInJob) {
                throw fail(where + " 是职业 " + classId + " 的第 " + rowInJob + " 行，slotInJob 却是 "
                        + slotInJob + " —— 槽序必须等于该职业内的行序（面板序）");
            }
            rowsSeenOfJob.put(classId, rowInJob + 1);

            String expectDir = SkillKeys.classDirOfJob(classId);
            if (!expectDir.equals(classDir)) {
                throw fail(where + ".classDir 期望 '" + expectDir + "'（职业号 " + classId + "），实得 '"
                        + classDir + "'");
            }
            if (macro != null) {
                if (macro.isBlank()) {
                    throw fail(where + ".macro 是空串 —— 没有宏的行请写 null，别写空串");
                }
                if (!macros.containsKey(macro)) {
                    throw fail(where + ".macro '" + macro + "' 不在 macros 段里（共 " + macros.size() + " 条）");
                }
                if (!macroSeen.add(macro)) {
                    throw fail(where + ".macro '" + macro + "' 重复出现在两行上");
                }
            }
            int element0 = intOf(requiredNode(n, "element0", where), where + ".element0");
            if (element0 != 0 && element0 != 1) {
                // 源码里它只被当布尔读（`if (…Element[0])`），值域就 0/1；其它值说明抽取口径变了
                throw fail(where + ".element0 期望 0 或 1，实得 " + element0);
            }
            out.add(new Skill(skillId, classId, slotInJob, tier, slotInTier, macro, iconFile, name,
                    constName, reqLv, useCode, classDir, element0));
        }
        for (int job = 1; job <= JOBS; job++) {
            int rows = rowsSeenOfJob.getOrDefault(job, 0);
            if (rows != SLOTS_PER_JOB) {
                throw fail("职业 " + job + " 只有 " + rows + " 行技能（每职业 " + SLOTS_PER_JOB + " 行）");
            }
        }
        return out;
    }

    /** `skillIdHex` 必须是 `0x` + 6 位 hex、且与 `skillId` **同值**。 */
    private static void checkHex(String text, int skillId, String where) {
        if (text.length() != HEX_TEXT_LEN || !text.startsWith("0x")) {
            // ⚠ 生成物那列字母是大写（`0x0B0101`），键里是小写（`0x0b0101`）—— 这里只比数，不比大小写
            throw fail(where + ".skillIdHex 形如 '0x' + 6 位 hex，实得 '" + text + "'");
        }
        int parsed = 0;
        for (int i = 2; i < text.length(); i++) {
            int d = Character.digit(text.charAt(i), 16);
            if (d < 0) {
                throw fail(where + ".skillIdHex 含非 hex 字符，实得 '" + text + "'");
            }
            parsed = (parsed << 4) | d;
        }
        if (parsed != skillId) {
            throw fail(where + ".skillIdHex '" + text + "' 与 skillId " + hex(skillId) + " 不是同一个数");
        }
    }

    /* ─────────────── skills 段的三个索引（载入时建一次，之后只读） ─────────────── */

    private static Map<Integer, Skill> indexById(List<Skill> rows) {
        Map<Integer, Skill> m = new LinkedHashMap<>();
        for (Skill s : rows) {
            m.put(s.skillId(), s);
        }
        return m;
    }

    private static Map<Integer, Skill> indexByJobSlot(List<Skill> rows) {
        Map<Integer, Skill> m = new LinkedHashMap<>();
        for (Skill s : rows) {
            m.put(jobSlotKey(s.classId(), s.slotInJob()), s);
        }
        return m;
    }

    private static Map<Integer, List<Skill>> indexByJob(List<Skill> rows) {
        Map<Integer, List<Skill>> m = new LinkedHashMap<>();
        for (Skill s : rows) {
            m.computeIfAbsent(s.classId(), k -> new ArrayList<>()).add(s);
        }
        Map<Integer, List<Skill>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Skill>> e : m.entrySet()) {
            List<Skill> inJob = new ArrayList<>(e.getValue());
            inJob.sort(Comparator.comparingInt(Skill::slotInJob));
            out.put(e.getKey(), List.copyOf(inJob));
        }
        return out;
    }

    private static Map<String, Skill> indexByMacro(List<Skill> rows) {
        Map<String, Skill> m = new LinkedHashMap<>();
        for (Skill s : rows) {
            if (s.macro() != null) {
                m.put(s.macro(), s);
            }
        }
        return m;
    }

    /** (职业, 槽) 的合成键：职业 + 槽在一字节内，不会撞。 */
    private static int jobSlotKey(int classId, int slotInJob) {
        return (classId << ID_SEGMENT_BITS) | slotInJob;
    }

    private static String hex(int id) {
        return "0x" + String.format(Locale.ROOT, "%06x", id);
    }

    /* ─────────────── JSON 取值助手（每一步都带"键名 + 期望 + 实得"） ─────────────── */

    private static String slotKey(String group, int tier, int slotInTier) {
        return group + "/" + tier + "-" + slotInTier;
    }

    private static String dimsStr(int[] dims) {
        StringBuilder sb = new StringBuilder();
        for (int d : dims) {
            sb.append('[').append(d).append(']');
        }
        return sb.toString();
    }

    private static JsonNode requiredNode(JsonNode parent, String key, String where) {
        JsonNode n = parent.get(key);
        if (n == null || n.isNull()) {
            throw fail(where + " 缺少键 '" + key + "'（实得 " + describe(parent.get(key)) + "）");
        }
        return n;
    }

    private static JsonNode requiredObject(JsonNode parent, String key, String where) {
        JsonNode n = requiredNode(parent, key, where);
        if (!n.isObject()) {
            throw fail(where + "." + key + " 期望对象，实得 " + describe(n));
        }
        return n;
    }

    private static JsonNode requiredArray(JsonNode parent, String key, String where) {
        JsonNode n = requiredNode(parent, key, where);
        if (!n.isArray()) {
            throw fail(where + "." + key + " 期望数组，实得 " + describe(n));
        }
        return n;
    }

    private static String requiredText(JsonNode parent, String key, String where) {
        JsonNode n = requiredNode(parent, key, where);
        if (!n.isTextual()) {
            throw fail(where + "." + key + " 期望字符串，实得 " + describe(n));
        }
        return n.asText();
    }

    private static int requiredCount(JsonNode counts, String key, int expected) {
        int actual = intOf(requiredNode(counts, key, "counts"), "counts." + key);
        if (actual != expected) {
            throw fail("counts." + key + " 期望 " + expected + "，实得 " + actual
                    + " —— 载到的不是这份生成物（或生成器口径变了，两端需同步）");
        }
        return actual;
    }

    private static int intOf(JsonNode n, String where) {
        if (n == null || !n.isNumber() || !n.canConvertToInt()) {
            throw fail(where + " 期望整数，实得 " + describe(n));
        }
        return n.asInt();
    }

    private static double numOf(JsonNode n, String where) {
        if (n == null || !n.isNumber()) {
            throw fail(where + " 期望数字，实得 " + describe(n));
        }
        return n.asDouble();
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String describe(JsonNode n) {
        if (n == null) {
            return "缺键";
        }
        if (n.isNull()) {
            return "null";
        }
        String s = n.toString();
        return s.length() > 60 ? n.getNodeType() + " " + s.substring(0, 60) + "…" : n.getNodeType() + " " + s;
    }

    private static <T> T requireLoaded(T value, String what) {
        if (value == null) {
            throw fail(what + " 还没载入 —— 先 load()（Spring 下是 @PostConstruct，测试里要显式调）");
        }
        return value;
    }

    private static IllegalStateException fail(String message) {
        return new IllegalStateException("技能数据 " + RESOURCE + " 校验失败：" + message);
    }
}
