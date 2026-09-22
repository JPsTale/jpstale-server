package org.jpstale.server.web.map;

import lombok.Getter;
import org.jpstale.server.web.admin.AdminQueryParams;

import java.util.Map;

/**
 * 地图列表接口的筛选参数 —— 五组固定条件：名字 / 地形类型 / 等级要求 / PvP / 是否有刷怪配置。
 *
 * <p>
 * 公共部分（`sort`/`order`/`page`/`size`、空串=没填、分页夹紧、未知键 400）在 {@link AdminQueryParams}。
 *
 * <p>
 * ⚠ `hasSpawn` **不是本表的列**：它是"`mapmonster` 里有没有这张图的行"（48/63 张图有）。
 * 63 张图，判定在 Java 侧算（与 AdminMapService 的 `spawn.configured` 同一判据）。
 */
@Getter
public final class MapQueryParams extends AdminQueryParams {

    /** 名字：同时匹配 `name` 与 `shortname`（旧版页面就是"按名称 / shortName 搜索"）。 */
    private String nameLike;
    /** 地形类型：`maplist.typemap` 的**原文**（`Cities` / `Underworld` / …，13 种）。 */
    private String typeMap;
    private Integer levelMin;
    private Integer levelMax;
    /** 是否 PvP：`pvp` 的 0/1 原值。 */
    private Integer pvp;
    /** 是否有刷怪配置（`mapmonster` 里有这张图的行）。null = 不限。 */
    private Boolean hasSpawn;

    private MapQueryParams() {
    }

    public static MapQueryParams parse(Map<String, String> raw) {
        MapQueryParams q = new MapQueryParams();
        each(raw, (key, value) -> {
            if (q.commonKey(key, value)) {
                return;     // sort / order / page / size
            }
            switch (key) {
                case "name_like" -> q.nameLike = value.trim();
                case "typemap" -> q.typeMap = value.trim();
                case "level_min" -> q.levelMin = intOf(key, value);
                case "level_max" -> q.levelMax = intOf(key, value);
                case "pvp" -> q.pvp = intOf(key, value);
                case "has_spawn" -> q.hasSpawn = boolOf(key, value);
                default -> throw new IllegalArgumentException("未知的查询参数：" + key);
            }
        });
        q.checkNotReversed("level", q.levelMin, q.levelMax);
        q.finish(MapColumnRegistry.REGISTRY);
        return q;
    }

    private static Boolean boolOf(String key, String value) {
        String v = value.trim().toLowerCase();
        if (v.equals("true") || v.equals("1")) {
            return Boolean.TRUE;
        }
        if (v.equals("false") || v.equals("0")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("参数 " + key + " 只能是 true/false（收到：" + value + "）");
    }
}
