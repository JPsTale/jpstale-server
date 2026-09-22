package org.jpstale.server.web.npc;

import lombok.Getter;
import org.jpstale.server.web.admin.AdminQueryParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NPC 列表接口的筛选参数 —— 四组固定条件：名字 / 所在地图 / 是否商人 / 事件类型。
 *
 * <p>
 * 公共部分（`sort`/`order`/`page`/`size`、空串=没填、分页夹紧、未知键 400）在 {@link AdminQueryParams}。
 *
 * <p>
 * ⚠ 其中两组**不是本表的列**：
 * <ul>
 *   <li>`map` —— 经 `gamedb.mapnpc`（`idnpc` = `npclist.id`、`stage` = `maplist.id`）反查；</li>
 *   <li>`merchant` —— "三个商店列任一非空"，判据与运行时 `NpcShopService.isMerchant(NpcList)` **同一条**
 *       （那条是 Java 侧的；这里必须下推到 SQL 才能分页，所以是同一判据的两个表达，改一边要改另一边）。</li>
 * </ul>
 */
@Getter
public final class NpcQueryParams extends AdminQueryParams {

    private String nameLike;
    /** 地图：`maplist.id`。 */
    private Integer map;
    /** 是否商人（三个商店列任一非空）。null = 不限。 */
    private Boolean merchant;
    /** 事件类型：`npclist.eventtype` 的数据库原值。 */
    private Integer eventType;
    /**
     * **内名称精确列表**（逗号分隔）—— 供"按本地化名检索"用。
     *
     * <p>
     * 为什么要有它：`npclist.name` 是内部键（`blacksmith_drol`），而界面显示的是本地化名
     *（客户端 `npc.*`，我们镜像成 `npcName.*`，键就是内名称）。管理员凭记忆搜"铁匠"时，
     * 页面先把本地化名反查成一批内名称，用本参数把这一批一次性交给服务端 ——
     * 服务端不必知道任何显示名（那是页面的语言表，不是数据）。
     */
    private List<String> names;

    private NpcQueryParams() {
    }

    /**
     * @throws IllegalArgumentException 未知键、非整数、非布尔的 merchant
     */
    public static NpcQueryParams parse(Map<String, String> raw) {
        NpcQueryParams q = new NpcQueryParams();
        each(raw, (key, value) -> {
            if (q.commonKey(key, value)) {
                return;     // sort / order / page / size
            }
            switch (key) {
                case "name_like" -> q.nameLike = value.trim();
                case "map" -> q.map = intOf(key, value);
                case "eventtype" -> q.eventType = intOf(key, value);
                case "names" -> q.names = namesOf(value);
                case "merchant" -> q.merchant = boolOf(key, value);
                default -> throw new IllegalArgumentException("未知的查询参数：" + key);
            }
        });
        q.finish(NpcColumnRegistry.REGISTRY);
        return q;
    }

    /** 逗号分隔的内名称列表；空白项丢弃，数量上限 200（超出说明查询本身没意义）。 */
    private static List<String> namesOf(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String n = part.trim();
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        if (out.size() > 200) {
            throw new IllegalArgumentException("names 一次最多 200 个（收到 " + out.size() + " 个）");
        }
        return List.copyOf(out);
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
