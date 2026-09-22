package org.jpstale.server.web.item;

import org.jpstale.common.service.item.ItemClass;
import org.jpstale.server.common.enums.character.CharacterJob;
import org.jpstale.server.common.enums.item.WeaponClass;
import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.ColumnSemantics.Bit;
import org.jpstale.server.web.admin.ColumnSemantics.Kind;
import org.jpstale.server.web.admin.ColumnSemantics.Option;
import org.jpstale.server.web.admin.ColumnSemantics.Semantics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列的**取值语义**（枚举 / 布尔 / 位掩码 / 成对区间）—— 让接口给出"值是哪个语义"，而不是把裸数字丢给使用者。
 *
 * <p>
 * ⚠ 语义的**类型**（{@link Kind} / {@link Option} / {@link Bit} / {@link Semantics}）定义在共用层
 * {@link ColumnSemantics} —— 物品与怪物只需各登记自己的**取值表**；本类就是物品那张表。
 *
 * <p>
 * 用户 2026-09-21：① 页面上一片裸数字（`primaryspec=6`、`classitem=6`…）看不懂；
 * ② **不许硬编码文案** —— "我的 web 页面后面怎么搞 i18n"。
 *
 * <p>
 * ⇒ 本类**只产出 translate key，不产出任何可显示文案**（与 {@code Result.msg} 的既有约定一致：
 * 服务端给 key、客户端翻译）。文案表在页面侧：`static/i18n/{zh,en}.json`；
 * 其中属性名的 key 与客户端 `jpstale-client/src/locales/zh.json` 的 `itemtip.*` **同名同义**，将来可合表。
 *
 * <p>
 * 每个 key 都注明依据，**不猜**：
 * <ul>
 *   <li>职业（`primaryspec` / `addspecclass1..12`）→ {@link CharacterJob}（AGENTS 纠错 #13 + 客户端 SPEC_JOBS）</li>
 *   <li>`weaponclass` → {@link WeaponClass}（`NONE/MELEE/RANGED/MAGIC`，原版 `EWeaponClass`）</li>
 *   <li>`classitem` → {@link ItemClass} 的位常量（原版 `sinItem.h:16-32` 的 `INVENTORY_POS_*`）；
 *       除候选值外还给**位表**，于是任意组合值都能由客户端按 key 拼出来（服务端不拼串）</li>
 *   <li>`modelposition` → `CharacterAppearance` 的注释"武器挂点（2左/4右/0无）"；
 *       ⚠ 库里还有 **8**（159 件），**代码与注释都没定义它**（客户端只判 `===2 ? 左 : 右`）
 *       —— 故其 key 的文案写"未定义"，**不编一个含义出来**</li>
 *   <li>`cannotdrop` → 0/1 布尔（文案用通用的"是/否"）</li>
 *   <li>成对区间（`*min`/`*max`，以及**交叉配对**的攻击力）→ `rowLabelKey` + `rowPart`</li>
 * </ul>
 */
public final class ItemColumnSemantics {

    /** BY_COLUMN 的默认值：无任何语义的数字列（语义**类型**定义在共用层，见类注释）。 */
    private static final Semantics NUMBER = ColumnSemantics.NUMBER;

    private static final Map<String, Semantics> BY_COLUMN = new LinkedHashMap<>();


    static {
        // ---- 职业：primaryspec（单值）与 addspecclass1..12（布尔位）----
        List<Option> jobOptions = new ArrayList<>();
        jobOptions.add(new Option(0, "admin.item.noJob"));
        for (CharacterJob job : CharacterJob.values()) {
            jobOptions.add(new Option(job.getNumber(), "job." + job.getNumber()));
        }
        jobOptions.add(new Option(CharacterJob.SPEC_SLOT_COUNT, "admin.item.slot12"));
        BY_COLUMN.put("primaryspec", new Semantics(Kind.ENUM, List.copyOf(jobOptions), List.of(), List.of(),
                "admin.item.primarySpec", 0, null));
        for (int i = 1; i <= CharacterJob.SPEC_SLOT_COUNT; i++) {
            BY_COLUMN.put("addspecclass" + i, Semantics.plain(Kind.BOOL));
        }

        // ---- weaponclass ----
        List<Option> weaponClasses = new ArrayList<>();
        for (WeaponClass wc : WeaponClass.values()) {
            weaponClasses.add(new Option(wc.getValue(), "enum.weaponClass." + wc.getValue()));
        }
        BY_COLUMN.put("weaponclass", new Semantics(Kind.ENUM, List.copyOf(weaponClasses), List.of(), List.of(), null, 0, null));

        // ---- classitem（槽位位掩码）：候选值 + 位表 ----
        BY_COLUMN.put("classitem", new Semantics(Kind.ENUM, classItemOptions(), List.of(), classItemBits(), null, 0, null));

        // ---- modelposition ----
        List<Option> positions = new ArrayList<>();
        for (int v : new int[]{0, 2, 4, 8}) {
            positions.add(new Option(v, "enum.modelPosition." + v));
        }
        BY_COLUMN.put("modelposition", new Semantics(Kind.ENUM, List.copyOf(positions), List.of(), List.of(), null, 0, null));

        BY_COLUMN.put("cannotdrop", Semantics.plain(Kind.BOOL));

        // ---- 游戏内展示用的属性名 + 成对区间 ----
        // key 与客户端串表同名同义（`jpstale-client/src/locales/zh.json` 的 `itemtip.*`）。
        // ⚠ 两个容易搞反的：客户端里 `absorb` = 防御、`def` = 躲闪（不是反过来）。
        namedPair("integritymin", "integritymax", "itemtip.durability");
        namedPair("atkratingmin", "atkratingmax", "itemtip.hit");
        namedPair("blockmin", "blockmax", "itemtip.block");
        // block 是**百分比**：客户端显示 `${Math.round(it.blockRating / 10)}%`，而 wire = DB × 10
        // （ItemNetworkHandler 序列化）⇒ 显示值就是 DB 值，只是缺个 '%'
        BY_COLUMN.put("blockmin", BY_COLUMN.get("blockmin").withUnit("%"));
        BY_COLUMN.put("blockmax", BY_COLUMN.get("blockmax").withUnit("%"));
        namedPair("absorbmin", "absorbmax", "itemtip.absorb");
        namedPair("defensemin", "defensemax", "itemtip.def");
        namedPair("runspeedmin", "runspeedmax", "itemtip.speed");
        namedPair("organicmin", "organicmax", "itemtip.resBionic");
        namedPair("firemin", "firemax", "itemtip.resFire");
        namedPair("frostmin", "frostmax", "itemtip.resIce");
        namedPair("lightningmin", "lightningmax", "itemtip.resLightning");
        namedPair("poisonmin", "poisonmax", "itemtip.resPoison");
        namedPair("addhpmin", "addhpmax", "itemtip.incLife");
        namedPair("addmpmin", "addmpmax", "itemtip.incMana");
        namedPair("addstmmin", "addstmmax", "itemtip.incStm");
        namedPair("regenerationhpmin", "regenerationhpmax", "itemtip.regenLife");
        namedPair("regenerationmpmin", "regenerationmpmax", "itemtip.regenMana");
        namedPair("regenerationstmmin", "regenerationstmmax", "itemtip.regenStm");
        namedPair("recoveryhpmin", "recoveryhpmax", "itemtip.recHp");
        namedPair("recoverympmin", "recoverympmax", "itemtip.recMp");
        namedPair("recoverystmmin", "recoverystmmax", "itemtip.recStm");

        // ⚠ 攻击力是**交叉配对**（不是同一列的 min/max）：小攻 = atkpow1min~atkpow2min、
        //    大攻 = atkpow1max~atkpow2max —— 与客户端 tooltip 的 `damageMin-damageMax` 同源。
        //    客户端串表里没有"小/大"之分（游戏内只显示一行攻击力），故另起两个 key。
        namedRow("atkpow1min", "admin.item.atkSmall", 1);
        namedRow("atkpow2min", "admin.item.atkSmall", 2);
        namedRow("atkpow1max", "admin.item.atkLarge", 1);
        namedRow("atkpow2max", "admin.item.atkLarge", 2);

        // 单值列
        BY_COLUMN.put("atkspeed", named("itemtip.attackSpeed"));
        // 必杀是**百分比**（客户端 ItemInfo：`${it.critical}%`，无缩放；库里的 6 就是 6%）
        BY_COLUMN.put("critical", named("itemtip.crit").withUnit("%"));
        BY_COLUMN.put("range", named("itemtip.range"));
        // 单值列：客户端串表里**没有**这几项，用用户 2026-09-21 提供的日服汉化补
        //（`sinAbilityName` 的"药水存放数量"；重量/价格/药水数量 由用户给出用词）
        BY_COLUMN.put("weight", named("admin.item.weight"));
        BY_COLUMN.put("price", named("admin.item.price"));
        BY_COLUMN.put("potionspace", named("admin.item.potionSpace"));
        BY_COLUMN.put("potioncount", named("admin.item.potionCount"));
        // 需求六项
        BY_COLUMN.put("reqlevel", named("itemtip.reqLv"));
        BY_COLUMN.put("reqstrength", named("itemtip.reqStr"));
        BY_COLUMN.put("reqspirit", named("itemtip.reqSpirit"));
        BY_COLUMN.put("reqtalent", named("itemtip.reqTalent"));
        BY_COLUMN.put("reqagility", named("itemtip.reqAgility"));
        BY_COLUMN.put("reqhealth", named("itemtip.reqHealth"));

        // ---- 职业特效增益（31 列）----
        // 名字同样取自日服/英文的 `sinSpecialName`（逐位对应）：
        //   0 Spec. ATK SPD / 1 Spec. CRIT / 2 Spec. DEF RTG / 3 Spec. ABS RTG / 4 Spec. BLK RTG /
        //   6 Spec. SPD / 15 Spec. ATK POW / 16 Spec. ATK RTG / 17 Spec. RNG /
        //   28 HP Recovery / 29 MP Recovery / 30 STM Recovery
        // ⚠ 原版给它们加了 "Spec." 前缀（因为 tooltip 里基础属性与特效追加在同一张表），
        //    用户 2026-09-21 指出**不许省掉 Spec.**（英文表里它就是原文的一部分）⇒
        //    特效行用**独立 key**（itemtip.spec*），zh 照日服表、en 照英文表，各自逐条照抄。
        // ⚠ 这几对也**消掉了我先前的疑虑**：DEF RTG=躲避 与 ABS RTG=防御 分得很清楚，
        //    与基础属性里 `defensemin/max`→躲避、`absorbmin/max`→防御 的归属**一致**。
        namedPair("addspecrunspeedmin", "addspecrunspeedmax", "itemtip.specSpeed");
        namedPair("addspecabsorbmin", "addspecabsorbmax", "itemtip.specAbsorb");
        namedPair("addspecdefensemin", "addspecdefensemax", "itemtip.specDef");
        namedPair("addspecatkpowermin", "addspecatkpowermax", "itemtip.specAtk");
        namedPair("addspecatkratingmin", "addspecatkratingmax", "itemtip.specHit");
        namedPair("addspecmpregenmin", "addspecmpregenmax", "itemtip.specRegenMana");
        BY_COLUMN.put("addspecatkspeed", named("itemtip.specAttackSpeed"));
        BY_COLUMN.put("addspeccritical", named("itemtip.specCrit").withUnit("%"));
        BY_COLUMN.put("addspechpregen", named("itemtip.specRegenLife"));
        BY_COLUMN.put("addspecstmregen", named("itemtip.specRegenStm"));
        BY_COLUMN.put("addspecblock", named("itemtip.specBlock").withUnit("%"));
        BY_COLUMN.put("addspecrange", named("itemtip.specRange"));
    }

    private ItemColumnSemantics() {
    }

    /** 该列的语义；未登记的列返回 NUMBER。 */
    public static Semantics of(String column) {
        return BY_COLUMN.getOrDefault(column, NUMBER);
    }

    // ------------------------------------------------------------------
    // 登记辅助
    // ------------------------------------------------------------------

    private static Semantics named(String rowLabelKey) {
        return new Semantics(Kind.NUMBER, List.of(), List.of(), List.of(), rowLabelKey, 0, null);
    }

    /** 登记一对「本列 min / 本列 max」的同名区间行（行内第 1、2 个）。 */
    private static void namedPair(String minColumn, String maxColumn, String rowLabelKey) {
        namedRow(minColumn, rowLabelKey, 1);
        namedRow(maxColumn, rowLabelKey, 2);
    }

    /** 登记一列的"行名 key + 行内位置"。保留该列已有的 kind/options/bits。 */
    private static void namedRow(String column, String rowLabelKey, int rowPart) {
        Semantics old = BY_COLUMN.getOrDefault(column, NUMBER);
        BY_COLUMN.put(column, old.withRow(rowLabelKey, rowPart));
    }

    // ------------------------------------------------------------------
    // classitem：候选值 + 位表
    // ------------------------------------------------------------------

    /**
     * `classitem` 的候选值（库里实测出现过的取值 ∪ 两个单戒指位）。
     *
     * <p>
     * 组合值（如 6 = 左手|右手）用**专门的 key**：它的常用叫法是"双手武器"而不是"左手+右手"。
     * 不在候选里的组合值由客户端按 {@link #classItemBits()} 拼。
     */
    private static List<Option> classItemOptions() {
        Set<Integer> values = new java.util.LinkedHashSet<>(List.of(
                0, ItemClass.BOX, ItemClass.OFF_HAND, ItemClass.ONE_HAND_WEAPON, ItemClass.TWO_HAND_WEAPON,
                ItemClass.ARMOR, ItemClass.BOOTS, ItemClass.GLOVES,
                ItemClass.LRING, ItemClass.RRING, ItemClass.RING,
                ItemClass.SHELTOM, ItemClass.AMULET, ItemClass.ARMLET,
                ItemClass.POTION, ItemClass.COSTUME));
        List<Option> out = new ArrayList<>(values.size());
        for (int v : values) {
            out.add(new Option(v, "enum.classItem." + v));
        }
        return List.copyOf(out);
    }

    /** `classitem` 的位表（每位一个 key）—— 客户端用它拼出任意组合值，故服务端不需要拼串。 */
    private static List<Bit> classItemBits() {
        return List.of(
                new Bit(ItemClass.BOX, "enum.classItemBit.box"),
                new Bit(ItemClass.LHAND, "enum.classItemBit.lhand"),
                new Bit(ItemClass.RHAND, "enum.classItemBit.rhand"),
                new Bit(ItemClass.ARMOR, "enum.classItemBit.armor"),
                new Bit(ItemClass.BOOTS, "enum.classItemBit.boots"),
                new Bit(ItemClass.GLOVES, "enum.classItemBit.gloves"),
                new Bit(ItemClass.LRING, "enum.classItemBit.lring"),
                new Bit(ItemClass.RRING, "enum.classItemBit.rring"),
                new Bit(ItemClass.SHELTOM, "enum.classItemBit.sheltom"),
                new Bit(ItemClass.AMULET, "enum.classItemBit.amulet"),
                new Bit(ItemClass.ARMLET, "enum.classItemBit.armlet"),
                new Bit(ItemClass.TWO_HAND_FLAG, "enum.classItemBit.twoHandFlag"),
                new Bit(ItemClass.POTION, "enum.classItemBit.potion"),
                new Bit(ItemClass.COSTUME, "enum.classItemBit.costume"),
                new Bit(ItemClass.WING_RIGHT, "enum.classItemBit.wingRight"),
                new Bit(ItemClass.EARRING_L, "enum.classItemBit.earringL"),
                new Bit(ItemClass.EARRING_R, "enum.classItemBit.earringR"));
    }
}
