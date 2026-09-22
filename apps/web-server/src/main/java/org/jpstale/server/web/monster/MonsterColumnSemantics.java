package org.jpstale.server.web.monster;

import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.ColumnSemantics.Kind;
import org.jpstale.server.web.admin.ColumnSemantics.Semantics;
import org.jpstale.server.web.admin.ColumnSemantics.TextOption;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `gamedb.monsterlist` 的列取值语义：显示时把裸数字/字符串翻成名字，编辑时给下拉或勾选。
 *
 * <p>
 * ⚠ 语义的**类型**定义在共用层 {@link ColumnSemantics}，本类只是怪物这张表。**只产出 translate key，
 * 不产出可显示文案**（文案在页面侧 `static/i18n/{zh,en}.json`）。
 *
 * <p>
 * 命名依据（**不猜**）：
 * <ul>
 *   <li>与物品同名的量**复用客户端串表的 key**，避免同一概念两套词：
 *       `defense`→`itemtip.def`（躲避）、`absorb`→`itemtip.absorb`（防御）、`attackrating`→`itemtip.hit`、
 *       `block`→`itemtip.block`、`attackspeed`→`itemtip.attackSpeed`、`attackrange`→`itemtip.range`、
 *       `movespeed`→`itemtip.speed`、五种抗性 → `itemtip.res*`。
 *       ⚠ 物品页那两个容易搞反的对应关系（`def`=躲避 / `absorb`=防御）在这里**沿用同一套**。</li>
 *   <li>`monstertype` / `propertymon` 是**文本枚举**，取值逐字来自数据库实测
 *       （本性 `Good`/`Normal`/`Neutral`/`Evil`；属性 `Demon`/`Normal`/`Machine`/`Mutant`/`Undead`）。
 *       ⚠ 库里还有一条小写 `good`（1 行）—— 不在候选里，界面按原值显示、不归并（忠于数据库）。</li>
 *   <li>其余列名按**字段名本身的含义**起名（`stunchance`→眩晕几率、`viewsight`→视野…）；
 *       含义不明的**不起名**，界面回退显示列名（`healthpoint`、`magic`、`stage`、`spawntime`…）。</li>
 *   <li>单位只给有依据的：`potionpercent` 的名字里就写着 percent。</li>
 * </ul>
 */
public final class MonsterColumnSemantics {

    private static final Semantics NUMBER = ColumnSemantics.NUMBER;

    private static final Map<String, Semantics> BY_COLUMN = new LinkedHashMap<>();

    static {
        // ---- 文本枚举（本性 / 属性）----
        BY_COLUMN.put("monstertype", Semantics.textEnum(List.of(
                new TextOption("Good", "monster.type.good"),
                new TextOption("Normal", "monster.type.normal"),
                new TextOption("Neutral", "monster.type.neutral"),
                new TextOption("Evil", "monster.type.evil"))));
        BY_COLUMN.put("propertymon", Semantics.textEnum(List.of(
                new TextOption("Demon", "monster.property.demon"),
                new TextOption("Normal", "monster.property.normal"),
                new TextOption("Machine", "monster.property.machine"),
                new TextOption("Mutant", "monster.property.mutant"),
                new TextOption("Undead", "monster.property.undead"))));

        // ---- 布尔 ----
        BY_COLUMN.put("has_run", Semantics.plain(Kind.BOOL));

        // ---- 成对区间（同一列族的 min/max 合成一行）----
        namedPair("atkpowmin", "atkpowmax", "monster.atk");
        namedPair("spawnmin", "spawnmax", "monster.spawnCount");
        namedPair("specialhitpowermin", "specialhitpowermax", "monster.specialHitPower");

        // ---- 复用客户端串表 key 的单值列（与物品页同一套用词）----
        named("defense", "itemtip.def");            // 躲避（= 防御等级 DR，与物品页一致）
        named("absorb", "itemtip.absorb");          // 防御（= 吸收，与物品页一致）
        named("attackrating", "itemtip.hit");
        named("block", "itemtip.block");
        named("attackspeed", "itemtip.attackSpeed");
        named("attackrange", "itemtip.range");
        named("movespeed", "itemtip.speed");
        named("organic", "itemtip.resBionic");
        named("fire", "itemtip.resFire");
        named("ice", "itemtip.resIce");
        named("lightning", "itemtip.resLightning");
        named("poison", "itemtip.resPoison");

        // ---- 怪物专有（key 前缀 monster.）----
        named("level", "monster.level");
        named("hp", "monster.hp");
        named("exp", "monster.exp");
        named("stunchance", "monster.stunChance");
        named("perfectattackrate", "monster.perfectAttackRate");
        named("viewsight", "monster.viewSight");
        named("inteligence", "monster.intelligence");
        named("potionpercent", "monster.potionPercent", "%");
        named("potions", "monster.potions");
        named("has_run", "monster.canRun");
        named("specialskilltype", "monster.specialSkillType");
        named("specialskillhit", "monster.specialSkillHit");
        named("specialhitrate", "monster.specialHitRate");
        named("specialhitscope", "monster.specialHitScope");
    }

    private MonsterColumnSemantics() {
    }

    /** 该列的语义；未登记的列返回 NUMBER（界面按列名显示、按文本框编辑）。 */
    public static Semantics of(String column) {
        return BY_COLUMN.getOrDefault(column, NUMBER);
    }

    // ------------------------------------------------------------------
    // 登记辅助
    // ------------------------------------------------------------------

    private static void named(String column, String rowLabelKey) {
        named(column, rowLabelKey, null);
    }

    /**
     * 给一列登记行名（+可选单位），**保留它已有的 kind/候选** —— `has_run` 之类的列
     * 先是 BOOL、再被起名，若这里新造一个 NUMBER 语义就会把布尔语义冲掉
     * （物品侧同一个坑：`namedRow` 也是"保留旧 kind"）。
     */
    private static void named(String column, String rowLabelKey, String unit) {
        Semantics old = BY_COLUMN.getOrDefault(column, NUMBER);
        BY_COLUMN.put(column, new Semantics(old.kind(), old.options(), old.textOptions(), old.bits(),
                rowLabelKey, 0, unit));
    }

    /** 登记一对「本列 min / 本列 max」的同名区间行（行内第 1、2 个）。 */
    private static void namedPair(String minColumn, String maxColumn, String rowLabelKey) {
        named(minColumn, rowLabelKey);
        named(maxColumn, rowLabelKey);
        BY_COLUMN.put(minColumn, BY_COLUMN.get(minColumn).withRow(rowLabelKey, 1));
        BY_COLUMN.put(maxColumn, BY_COLUMN.get(maxColumn).withRow(rowLabelKey, 2));
    }
}
