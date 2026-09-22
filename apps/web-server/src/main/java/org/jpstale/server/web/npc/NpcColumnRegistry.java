package org.jpstale.server.web.npc;

import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.server.web.admin.ColumnRegistry;

import java.util.List;
import java.util.Set;

/**
 * `gamedb.npclist` 的列注册表（18 列，四段）。
 *
 * <p>
 * 段：身份（id/name/gamefile/teleportid）· 对话（message1..4）· 事件与任务（eventtype…questtypesubid）·
 * 商店清单（weaponshop/defenseshop/miscshop）。
 *
 * <p>
 * ⚠ 列语义大多**没有可依据的取值表**（`eventtype` 那 28 个码在原版源码里也找不到对照表）；
 * 所以那一批**不起名、不给候选**，界面按数字显示、按数字编辑 —— 不编含义（见 MonsterColumnSemantics 的同款处理）。
 */
public final class NpcColumnRegistry {

    /** 段的展示顺序。 */
    public static final List<String> SECTION_ORDER = List.of("Identity", "Messages", "Event", "Shop");

    /** 参与筛选的列：名字、事件类型（"所在地图"与"是否商人"不是本表的列，见 NpcQueryParams）。 */
    private static final Set<String> FILTERABLE = Set.of("name", "eventtype");

    /** 反射生成的列注册表（段名文案 key 前缀 `admin.npc.section.`，日志前缀 `NpcColumn`）。 */
    public static final ColumnRegistry REGISTRY = new ColumnRegistry(
            NpcList.class, SECTION_ORDER, NpcColumnRegistry::sectionOf, FILTERABLE,
            "admin.npc.section.", "NpcColumn");

    /** 段归属表。加列时**只需改这里**；漏改的列会落进 Unassigned 并在日志里报错。 */
    private static String sectionOf(String column) {
        if (List.of("id", "name", "gamefile", "teleportid").contains(column)) {
            return "Identity";
        }
        if (List.of("message1", "message2", "message3", "message4").contains(column)) {
            return "Messages";
        }
        if (List.of("eventtype", "eventparam", "skillquests", "questid",
                "questtypeid", "questtypesubid").contains(column)) {
            return "Event";
        }
        if (List.of("weaponshop", "defenseshop", "miscshop").contains(column)) {
            return "Shop";
        }
        return ColumnRegistry.SECTION_UNASSIGNED;
    }

    private NpcColumnRegistry() {
    }
}
