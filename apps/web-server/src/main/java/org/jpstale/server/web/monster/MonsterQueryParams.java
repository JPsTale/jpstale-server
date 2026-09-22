package org.jpstale.server.web.monster;

import lombok.Getter;
import org.jpstale.server.web.admin.AdminQueryParams;

import java.util.Map;

/**
 * 怪物列表接口的筛选参数 —— 四组固定条件（名字 / 等级 / 属性+本性 / 所在地图）。
 *
 * <p>
 * 公共部分（`sort`/`order`/`page`/`size`、空串=没填、分页夹紧、未知键 400）在
 * {@link AdminQueryParams} 里 —— 物品与怪物共用。
 *
 * <p>
 * ⚠ "所在地图"（`map`）**不是表里的列**：它按 `gamedb.mapmonster` 的刷怪配置反查
 * （`stage` = `maplist.id`，`monster1..12` 存的是怪物**名字**），口径与刷怪代码一致，见
 * {@code AdminMonsterService.applyFilters}。
 */
@Getter
public final class MonsterQueryParams extends AdminQueryParams {

    private String nameLike;
    private Integer levelMin;
    private Integer levelMax;
    /** 本性：`monsterlist.monstertype` 的**数据库原值**（`Good`/`Normal`/`Neutral`/`Evil`），精确匹配。 */
    private String monsterType;
    /** 属性：`monsterlist.propertymon` 的**数据库原值**（`Demon`/`Normal`/…），精确匹配。 */
    private String propertyMon;
    /** 地图：`maplist.id`。 */
    private Integer map;

    private MonsterQueryParams() {
    }

    /**
     * @throws IllegalArgumentException 未知键、非整数、区间倒置（min &gt; max）
     */
    public static MonsterQueryParams parse(Map<String, String> raw) {
        MonsterQueryParams q = new MonsterQueryParams();
        each(raw, (key, value) -> {
            if (q.commonKey(key, value)) {
                return;     // sort / order / page / size
            }
            switch (key) {
                case "name_like" -> q.nameLike = value.trim();
                case "level_min" -> q.levelMin = intOf(key, value);
                case "level_max" -> q.levelMax = intOf(key, value);
                case "monstertype" -> q.monsterType = value.trim();
                case "propertymon" -> q.propertyMon = value.trim();
                case "map" -> q.map = intOf(key, value);
                default -> throw new IllegalArgumentException("未知的查询参数：" + key);
            }
        });
        q.checkNotReversed("level", q.levelMin, q.levelMax);
        // sort 白名单 + 分页夹紧（夹紧同时是 LIMIT 的注入防线）
        q.finish(MonsterColumnRegistry.REGISTRY);
        return q;
    }
}
