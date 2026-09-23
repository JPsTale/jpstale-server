package org.jpstale.common.service.item;

import java.util.List;

/**
 * 怪物水晶（`sinGP1 = 0x08020000`）→ 召唤体模板的**分派表**。
 *
 * <p>
 * 与 {@link ForceOrb}／{@code TeleportService.ITEM_DESTINATIONS}／{@code AgeService.maxAgeKindOf} 同一形态：
 * **写在代码里的常量表，每行带依据**，不建库表（AGENTS #16：物品效果 = 代码里的分发表）。
 * 原版同样如此 —— `OnSever.cpp:302-323` 的 `srCristalMonster[]` 就是一张硬编码表，
 * 而 `CodeCount = ((dwItemCode & 0xFFFF) >> 8) - 1` 把「码」直接当表下标（一码一怪）。
 * 详见 {@code docs/召唤物系统-源码分析.md} §3.1／§4.1。
 *
 * <p>
 * ⚠ **与原版的一处有意偏离**：原版表里存的是**怪物名字**，服务端启动时靠
 * `lstrcmp` 回填模板指针（`OnSever.cpp:1776-1782`），名字对不上就
 * `return FALSE` → **水晶被静默消耗、什么也不出来**（该文 §9 的 D5/D6）。
 * 我们**按 `monsterlist.monsterid` 绑定**（§10.1 的建议），并且查不到模板时由调用方
 * 回一条可见的错误 —— 不复刻这条静默失败。
 *
 * <p>
 * 本期只覆盖「普通怪物水晶 + 神秘水晶」（GP101–GP113）。**未列入**的水晶（GP114-116 城堡兵、
 * GP117-121/125 事件档、GP2xx 灵魂石、11 职业新增的 boss 水晶）都会让 {@link #defOf} 返回
 * {@code null}，由调用方按「暂不支持」处理 —— **不是**"没有这个功能"的静默。
 */
public final class CrystalService {

    /** 怪物水晶家族（`sinItem.h`：`#define sinGP1 0x08020000`）。 */
    public static final int FAMILY = 0x08020000;

    /** 神秘水晶（`GP109`）：原版 `srCRISTAL_RANDOM = 8`，从权重池随机抽一只。 */
    public static final int MYSTIC_CRYSTAL = 0x08020900;

    /**
     * 召唤来源的一项：`monsterId` 是 `gamedb.monsterlist.monsterid`（**业务 id**，不是主键 id ——
     * 全库唯一，已核对），`weight` 是相对权重（原版 `srCristalMonster[].RndCount`）。
     */
    public record Roll(int monsterId, int weight) {
    }

    /**
     * 一条水晶定义：`crystalIdCode` → 权重池。
     *
     * <p>
     * 固定水晶的池只有一项（权重 100），神秘水晶的池是 8 项 ——
     * **用同一种「按权重抽」的机制表达两种水晶**，不做类型分支。
     */
    public record CrystalDef(int crystalIdCode, List<Roll> pool, String note) {

        /** 单怪水晶（池里唯一那项）。 */
        static CrystalDef fixed(int idCode, int monsterId, String note) {
            return new CrystalDef(idCode, List.of(new Roll(monsterId, 100)), note);
        }
    }

    /**
     * 神秘水晶的随机池 —— **原版 `srCristalMonster[0..7]` 的怪物与 `RndCount` 权重逐条照抄**
     * （`OnSever.cpp:303-310`）。召唤体取各自的专用模板（`*_Crystal`，`exp = 0`）。
     *
     * <p>
     * ⚠ **Hulk 的权重是 0，即抽不到它** —— 这不是笔误：原版权重合计
     * `20+20+15+15+15+10+5+0 = 100`，而 `RndNum = rand() % 100 ∈ [0,99]`，第 8 项需要
     * `RndNum ≥ 100`，永远不成立。原版客户端的物品说明里**却列了它**
     * （`SrcGame/src/sinbaram/sinItem.cpp:2529-2544`：`"Hopi, Duende Macabro, / Decoy, Bargon,
     * Decapitador, / Figon, Rei Hopi, Hulk"`）⇒ 说明文本与实际概率表不一致。
     * 我们**照原版数值**记录（不"顺手修好"：没有权威数据说该给它多少），
     * 并由 {@code CrystalServiceTest} 把这条钉成断言，免得日后被当成 bug"修"掉。
     */
    private static final List<Roll> MYSTIC_POOL = List.of(
        new Roll(1207, 20),   // 호피        Hopy_Crystal
        new Roll(1209, 20),   // 홉고블린    Hobgoblin_Crystal
        new Roll(1211, 15),   // 디코이      Decoy_Crystal
        new Roll(1213, 15),   // 바곤        Bargon_Crystal
        new Roll(1217, 15),   // 헤드커터    Head Cutter_Crystal
        new Roll(1219, 10),   // 파이곤      Figon_Crystal
        new Roll(1221, 5),    // 킹호피      King Hopy_Crystal
        new Roll(1223, 0));   // 헐크        Hulk_Crystal（权重 0 = 抽不到，见上）

    /**
     * 分派表：水晶码 → 召唤体。
     *
     * <p>
     * 每行的 `note` 记「原版表下标与怪名 → 我们的 `monsterlist` 行」。
     * 召唤体全部是 DB 里**专为此准备的 `*_Crystal` 模板**（`monstertype = 'Evil'`、`exp = 0`，
     * 血量/攻防/攻速/视野都已按召唤体调过）—— 已逐条核对存在且 `monsterid` 唯一。
     */
    private static final List<CrystalDef> CRYSTALS = List.of(
        CrystalDef.fixed(0x08020100, 1207, "GP101 独角兽/Hopy：原版 srCristalMonster[0] \"호피\" → monsterid 1207 Hopy_Crystal"),
        CrystalDef.fixed(0x08020200, 1209, "GP102 魔兽兵/Hobgoblin：原版 [1] \"홉고블린\" → 1209 Hobgoblin_Crystal"),
        CrystalDef.fixed(0x08020300, 1211, "GP103 浮灵/Decoy：原版 [2] \"디코이\" → 1211 Decoy_Crystal"),
        CrystalDef.fixed(0x08020400, 1213, "GP104 刀斧手/Bargon：原版 [3] \"바곤\" → 1213 Bargon_Crystal"),
        CrystalDef.fixed(0x08020500, 1217, "GP105 魔剑士/Head Cutter：原版 [4] \"헤드커터\" → 1217 Head Cutter_Crystal"),
        CrystalDef.fixed(0x08020600, 1219, "GP106 火灵王/Figon：原版 [5] \"파이곤\" → 1219 Figon_Crystal"),
        CrystalDef.fixed(0x08020700, 1221, "GP107 独角兽王/King Hopy：原版 [6] \"킹호피\" → 1221 King Hopy_Crystal"),
        CrystalDef.fixed(0x08020800, 1223, "GP108 绿巨人/Hulk：原版 [7] \"헐크\" → 1223 Hulk_Crystal"),
        new CrystalDef(MYSTIC_CRYSTAL, MYSTIC_POOL,
            "GP109 神秘水晶：原版 srCRISTAL_RANDOM=8 → srCristalMonster[0..7] 按 RndCount 抽一（OnSever.cpp:3480-3495）"),
        CrystalDef.fixed(0x08020A00, 1235, "GP110 守护圣徒/Guardian Saint：原版 [9] \"가디안 세인트\" → 1235 Guardian Saint_Crystal"),
        CrystalDef.fixed(0x08020B00, 1215, "GP111 大头蜘蛛/Web：原版 [10] \"웹\" → 1215 Web_Crystal"),
        CrystalDef.fixed(0x08020C00, 1225, "GP112 鬼影魔神/Dark Specter：原版 [11] \"다크 스펙터\" → 1225 Dark Specter_Crystal"),
        CrystalDef.fixed(0x08020D00, 1227, "GP113 铁甲狂魔/Iron Guard：原版 [12] \"아이언 가드\" → 1227 Iron Guard_Crystal"));

    private CrystalService() {
    }

    /** 该码是不是我们已实现的水晶（供调用方与客户端镜像表核对）。 */
    public static boolean isSupported(Integer idCode) {
        return defOf(idCode) != null;
    }

    /**
     * 水晶码 → 定义；**不是 `sinGP1` 族、或属于本期未实现的那几档（GP114+ 等）时返回 {@code null}**。
     *
     * <p>返回 null 是「这条链不处理它」，调用方要继续往下走（原版的其它分支 / 兜底提示），
     * 不是错误。
     */
    public static CrystalDef defOf(Integer idCode) {
        if (idCode == null || (idCode & 0xFFFF0000) != FAMILY) {
            return null;
        }
        for (CrystalDef d : CRYSTALS) {
            if (d.crystalIdCode() == idCode) {
                return d;
            }
        }
        return null;
    }

    /**
     * 按权重从池里抽一个召唤体（纯函数：`roll0to99` 由调用方给 `rand.nextInt(100)` 的结果，
     * 便于表驱动单测 —— 与 {@code AiEngine.decide} 同一做法）。
     *
     * <p>
     * 语义对齐原版 `OnSever.cpp:3487-3495` 的累积权重循环（那段的写法本身有瑕疵 ——
     * 它拿**已选中的** `CodeCount` 去判 `RndCount`、却累加 `cnt` 的权重，只是碰巧与原意等价；
     * 详见该文 §9 的 D2）。我们写正常写法，**不复刻那段**。
     *
     * @return 抽中的 `monsterid`；池为空或权重全为 0（数据有问题）时返回 {@code -1}，
     *         由调用方**可见地**报错，不静默挑一个
     */
    public static int rollSummon(CrystalDef def, int roll0to99) {
        if (def == null || def.pool().isEmpty()) {
            return -1;
        }
        int cumulative = 0;
        for (Roll r : def.pool()) {
            if (r.weight() <= 0) {
                continue;   // 权重 0 的项不可达（原版 Hulk 即如此）
            }
            cumulative += r.weight();
            if (roll0to99 < cumulative) {
                return r.monsterId();
            }
        }
        return -1;
    }

    /** 池里权重 > 0 的项（单测/日志用）。 */
    public static List<Roll> reachableOf(CrystalDef def) {
        return def == null ? List.of() : def.pool().stream().filter(r -> r.weight() > 0).toList();
    }

    /** 本期已实现的水晶码（升序），供客户端镜像表核对用。 */
    public static List<Integer> supportedCodes() {
        return CRYSTALS.stream().map(CrystalDef::crystalIdCode).sorted().toList();
    }
}
