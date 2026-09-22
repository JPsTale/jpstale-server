package org.jpstale.server.web.map;

import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.ColumnSemantics.Kind;
import org.jpstale.server.web.admin.ColumnSemantics.Semantics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `gamedb.maplist` 的列取值语义。
 *
 * <p>
 * 命名依据（**不猜**）：全按列名含义起名；`levelreq` 复用物品/怪物都在用的 `itemtip.reqLv`（同一概念一份词）。
 * `stagefile` 是**场景文件名**（63 张图实测全非空），不是路径，按原文显示。
 */
public final class MapColumnSemantics {

    private static final Semantics NUMBER = ColumnSemantics.NUMBER;

    private static final Map<String, Semantics> BY_COLUMN = new LinkedHashMap<>();

    static {
        named("name", "map.name");
        named("shortname", "map.shortName");
        named("typemap", "map.typeMap");
        named("levelreq", "itemtip.reqLv");
        named("stagefile", "map.stageFile");
        // pvp：0/1 开关（实测 63 张图里 1 张非零）—— 布尔，界面给勾选
        BY_COLUMN.put("pvp", Semantics.plain(Kind.BOOL));
    }

    private MapColumnSemantics() {
    }

    public static Semantics of(String column) {
        return BY_COLUMN.getOrDefault(column, NUMBER);
    }

    private static void named(String column, String rowLabelKey) {
        Semantics old = BY_COLUMN.getOrDefault(column, NUMBER);
        BY_COLUMN.put(column, new Semantics(old.kind(), old.options(), old.textOptions(), old.bits(),
                rowLabelKey, 0, old.unit()));
    }
}
