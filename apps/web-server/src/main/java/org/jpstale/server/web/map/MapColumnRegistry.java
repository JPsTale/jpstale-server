package org.jpstale.server.web.map;

import org.jpstale.dao.gamedb.entity.MapList;
import org.jpstale.server.web.admin.ColumnRegistry;

import java.util.List;
import java.util.Set;

/**
 * `gamedb.maplist` 的列注册表（7 列，三段）。
 *
 * <p>
 * 段：身份（id/name/shortname/typemap）· 规则（levelreq/pvp）· 资产（stagefile）。
 *
 * <p>
 * ⚠ `typemap` 是**文本**（实测取值：`Underworld 20 / Deserts 8 / Grasslands 6 / Cities 5 / …`，
 * 另有 `GM Room`、`Special Maps`、`Quest Map`）—— 直接显示原文，不做枚举。
 */
public final class MapColumnRegistry {

    /** 段的展示顺序。 */
    public static final List<String> SECTION_ORDER = List.of("Identity", "Rules", "Assets");

    /** 参与筛选的列（当前的管理端列表是前端过滤，这里先登记好，供将来下推）。 */
    private static final Set<String> FILTERABLE = Set.of("name", "shortname", "typemap", "levelreq", "pvp");

    /** 反射生成的列注册表（段名文案 key 前缀 `admin.map.section.`，日志前缀 `MapColumn`）。 */
    public static final ColumnRegistry REGISTRY = new ColumnRegistry(
            MapList.class, SECTION_ORDER, MapColumnRegistry::sectionOf, FILTERABLE,
            "admin.map.section.", "MapColumn");

    /** 段归属表。加列时**只需改这里**；漏改的列会落进 Unassigned 并在日志里报错。 */
    private static String sectionOf(String column) {
        if (List.of("id", "name", "shortname", "typemap").contains(column)) {
            return "Identity";
        }
        if (List.of("levelreq", "pvp").contains(column)) {
            return "Rules";
        }
        if (List.of("stagefile").contains(column)) {
            return "Assets";
        }
        return ColumnRegistry.SECTION_UNASSIGNED;
    }

    private MapColumnRegistry() {
    }
}
