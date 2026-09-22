package org.jpstale.server.web.monster;

import org.jpstale.dao.gamedb.entity.MonsterList;
import org.jpstale.server.web.admin.ColumnRegistry;

import java.util.List;
import java.util.Set;

/**
 * `gamedb.monsterlist` 的列注册表 —— 只放**怪物特有的部分**（段顺序、段归属、可筛列），
 * 反射与自检逻辑在共用的 {@link ColumnRegistry} 里。
 *
 * <p>
 * 53 列全部归档；**漏归类的列不会被藏起来**，而是落进 Unassigned 段并在启动日志里报 error
 * （见 {@link ColumnRegistry} 的自检）—— 免得"某列在界面上没有"静默存在。
 */
public final class MonsterColumnRegistry {

    /** 段的展示顺序。 */
    public static final List<String> SECTION_ORDER =
            List.of("Identity", "Combat", "Resistances", "AI", "Special", "Spawn", "Quest");

    /**
     * 参与筛选的列：名字、等级、本性、属性。
     *
     * <p>
     * ⚠ "所在地图"那组筛选**不是列**（它查的是 `mapmonster` 的刷怪配置），故不在这里
     * —— 见 {@link MonsterQueryParams}。
     */
    private static final Set<String> FILTERABLE = Set.of("name", "level", "monstertype", "propertymon");

    /** 反射生成的列注册表（段名文案 key 前缀 `admin.monster.section.`，日志前缀 `MonsterColumn`）。 */
    public static final ColumnRegistry REGISTRY = new ColumnRegistry(
            MonsterList.class, SECTION_ORDER, MonsterColumnRegistry::sectionOf, FILTERABLE,
            "admin.monster.section.", "MonsterColumn");

    /** 段归属表。加列时**只需改这里**；漏改的列会落进 Unassigned 并在日志里报错。 */
    private static String sectionOf(String column) {
        // 身份与外观：能唯一认出这只怪、以及它长什么样
        if (List.of("id", "monsterid", "name", "monstertype", "propertymon", "modelfile",
                "size", "sizeshadow", "glow", "cameray", "cameraz", "effect").contains(column)) {
            return "Identity";
        }
        // 战斗数值
        if (List.of("level", "hp", "healthpoint", "exp", "atkpowmin", "atkpowmax", "defense", "absorb",
                "block", "stunchance", "attackrating", "attackspeed", "attackrange",
                "perfectattackrate", "movespeed", "has_run").contains(column)) {
            return "Combat";
        }
        // 六种抗性
        if (List.of("organic", "lightning", "ice", "fire", "poison", "magic").contains(column)) {
            return "Resistances";
        }
        // AI 参数
        if (List.of("viewsight", "inteligence", "potionpercent", "potions").contains(column)) {
            return "AI";
        }
        // 特殊攻击（原版 SpecialSkill* 族）
        if (List.of("specialskilltype", "specialskillhit", "specialhitrate", "specialhitscope",
                "specialhitpowermin", "specialhitpowermax").contains(column)) {
            return "Special";
        }
        // 刷怪与掉落开关（"刷在哪张图"查的是 mapmonster，不是本表的列）
        if (List.of("spawntime", "spawnmin", "spawnmax", "dropispublic", "dropquantity").contains(column)) {
            return "Spawn";
        }
        // 任务关联
        if (List.of("questitemdrop", "questid", "questmap", "stage").contains(column)) {
            return "Quest";
        }
        return ColumnRegistry.SECTION_UNASSIGNED;
    }

    private MonsterColumnRegistry() {
    }
}
