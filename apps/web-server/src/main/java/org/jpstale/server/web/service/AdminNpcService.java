package org.jpstale.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.entity.MapList;
import org.jpstale.dao.gamedb.entity.MapNpc;
import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.dao.gamedb.mapper.MapListMapper;
import org.jpstale.dao.gamedb.mapper.MapNpcMapper;
import org.jpstale.dao.gamedb.mapper.NpcListMapper;
import org.jpstale.server.web.admin.AdminEntityService;
import org.jpstale.server.web.admin.ColumnRegistry;
import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.JsonValues;
import org.jpstale.server.web.npc.NpcColumnRegistry;
import org.jpstale.server.web.npc.NpcColumnSemantics;
import org.jpstale.server.web.npc.NpcQueryParams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NPC 管理（`gamedb.npclist`）：列表 / 详情 / 修改 + **商店清单** + **摆放（mapnpc）**。
 *
 * <p>
 * 三张表的关系（都实测过）：
 * <ul>
 *   <li>商店清单就是 `npclist` 自己的三列 `weaponshop / defenseshop / miscshop`：**空格分隔的物品码**，
 *       运行时由 {@code NpcShopService} 读（`NpcShopService.isMerchant` 判"三个列任一非空"）。
 *       ⇒ 与怪物掉落是**同一种数据**，解析一律走 {@link ItemCodeLookup}（一份实现）。</li>
 *   <li>摆放：`mapnpc`（`idnpc` = `npclist.id`，`stage` = `maplist.id`，外加 x/y/z/angle/enabled/onlygm）
 *       —— 实测 219 行**全部**能对上 npclist，属于"这个 NPC 站在哪几张图、哪个点、开不开"。
 *       本期**只读**（编辑摆放是另一件事：要动坐标与开关，属下一步）。</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminNpcService extends AdminEntityService<NpcList, NpcQueryParams> {

    /** 商店三列（列名即协议键，与"对外用数据库列名"一致）。 */
    private static final List<String> SHOP_COLUMNS = List.of("weaponshop", "defenseshop", "miscshop");

    /**
     * "是否商人"的 SQL 表达（三个商店列任一非空）。
     *
     * ⚠ 与运行时 `NpcShopService.isMerchant(NpcList)` 是**同一条判据的两个表达**：那条在 Java 侧，
     * 这里必须下推到 SQL 才能分页（不能把 204 行全捞出来在内存里筛）。改一边要改另一边。
     */
    private static final String MERCHANT_SQL =
            "(coalesce(weaponshop,'') <> '' OR coalesce(defenseshop,'') <> '' OR coalesce(miscshop,'') <> '')";

    private final NpcListMapper npcListMapper;
    private final MapNpcMapper mapNpcMapper;
    private final MapListMapper mapListMapper;
    private final ItemCodeLookup itemCodeLookup;

    public AdminNpcService(NpcListMapper npcListMapper, MapNpcMapper mapNpcMapper,
                           MapListMapper mapListMapper, ItemCodeLookup itemCodeLookup) {
        this.npcListMapper = npcListMapper;
        this.mapNpcMapper = mapNpcMapper;
        this.mapListMapper = mapListMapper;
        this.itemCodeLookup = itemCodeLookup;
    }

    @Override
    protected ColumnRegistry registry() {
        return NpcColumnRegistry.REGISTRY;
    }

    @Override
    protected BaseMapper<NpcList> mapper() {
        return npcListMapper;
    }

    @Override
    protected NpcList newPatch() {
        return new NpcList();
    }

    @Override
    protected ColumnSemantics.Semantics semanticsOf(String column) {
        return NpcColumnSemantics.of(column);
    }

    @Override
    protected String logTag() {
        return "[NpcAdmin]";
    }

    @Override
    protected String entityLabel() {
        return "NPC 定义";
    }

    @Override
    protected void applyFilters(QueryWrapper<NpcList> w, NpcQueryParams q) {
        if (q.getNameLike() != null) {
            w.apply("name ILIKE {0}", "%" + escapeLike(q.getNameLike()) + "%");
        }
        if (q.getEventType() != null) {
            w.eq("eventtype", q.getEventType());
        }
        if (q.getNames() != null && !q.getNames().isEmpty()) {
            // 内名称精确匹配（页面把"本地化名"反查成内名称后传进来，见 NpcQueryParams.names 的注释）
            w.in("name", q.getNames());
        }
        if (q.getMerchant() != null) {
            // 取反要连 NULL 一起算进来（coalesce 已把 NULL 当空串，故 NOT(...) 是安全的）
            w.apply(q.getMerchant() ? MERCHANT_SQL : "NOT " + MERCHANT_SQL);
        }
        if (q.getMap() != null) {
            List<Integer> npcIds = npcIdsOnMap(q.getMap());
            if (npcIds.isEmpty()) {
                // 该图没有 NPC ⇒ 结果必然为空。不能用 `IN ()`（MyBatis-Plus 会生成非法 SQL），
                // 也不能"跳过条件"（那会静默返回全表）。
                w.apply("1 = 0");
            } else {
                w.in("id", npcIds);
            }
        }
    }

    /** 该图上的 NPC id（经 `mapnpc` 反查，与列表筛选同一判据）。 */
    private List<Integer> npcIdsOnMap(int mapId) {
        List<Integer> ids = new ArrayList<>();
        for (MapNpc p : mapNpcMapper.selectList(new QueryWrapper<MapNpc>().eq("stage", mapId))) {
            if (p.getIdNpc() != null && !ids.contains(p.getIdNpc())) {
                ids.add(p.getIdNpc());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 筛选候选项
    // ------------------------------------------------------------------

    /** 筛选候选项：有 NPC 的地图（带计数）+ 事件类型（原值 + 计数）。 */
    public Map<String, Object> facets() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 地图：只给**有 NPC** 的图（没 NPC 的图在这个筛选下恒为空集）
        Map<Integer, Integer> countByStage = new LinkedHashMap<>();
        for (MapNpc p : mapNpcMapper.selectList(null)) {
            Integer stage = p.getStage();
            if (stage != null) {
                countByStage.merge(stage, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (MapList l : mapListMapper.selectList(new QueryWrapper<MapList>().orderByAsc("id"))) {
            Integer count = countByStage.get(l.getId());
            if (count == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", l.getId());
            m.put("name", l.getName());
            m.put("count", count);
            maps.add(m);
        }
        out.put("maps", maps);

        List<Map<String, Object>> rows = npcListMapper.selectMaps(
                new QueryWrapper<NpcList>()
                        .select("eventtype", "count(*) as cnt")
                        .groupBy("eventtype")
                        .orderByDesc("cnt"));
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("value", r.get("eventtype"));
            t.put("count", r.get("cnt"));
            types.add(t);
        }
        out.put("eventTypes", types);
        return out;
    }

    // ------------------------------------------------------------------
    // 商店清单
    // ------------------------------------------------------------------

    /**
     * 三个商店列的清单（每列各自一组物品）。
     *
     * @return null 表示 NPC 不存在；否则 `{isMerchant, weapon:[…], defense:[…], misc:[…]}`
     *         （每个条目 = `{code, itemId, idcode, name}`；`itemId` 是 **itemlist 主键**，用于跳转）
     */
    public Map<String, Object> shops(int id) {
        NpcList n = npcListMapper.selectById(id);
        if (n == null) {
            return null;
        }
        Map<String, ItemList> known = itemCodeLookup.byItemsStrings(List.of(
                nullToEmpty(n.getWeaponShop()), nullToEmpty(n.getDefenseShop()), nullToEmpty(n.getMiscShop())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isMerchant", isMerchant(n));
        out.put("weapon", shopGroup(n.getWeaponShop(), known));
        out.put("defense", shopGroup(n.getDefenseShop(), known));
        out.put("misc", shopGroup(n.getMiscShop(), known));
        return out;
    }

    /** 与运行时 `NpcShopService.isMerchant` 同一条判据（三个列任一非空）。 */
    private static boolean isMerchant(NpcList n) {
        return notBlank(n.getWeaponShop()) || notBlank(n.getDefenseShop()) || notBlank(n.getMiscShop());
    }

    /** 该列的码（按空白切分；NULL 与空串都得到空列表）。 */
    private static List<String> tokensByColumn(NpcList n, String column) {
        String raw = switch (column) {
            case "weaponshop" -> n.getWeaponShop();
            case "defenseshop" -> n.getDefenseShop();
            case "miscshop" -> n.getMiscShop();
            default -> null;
        };
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String t : raw.trim().split("\\s+")) {
                if (!t.isBlank()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** 两个码列表是否"语义相同"（数量、顺序、码本身都对得上，只有大小写不同也算相同）。 */
    private static boolean sameCodes(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static List<Map<String, Object>> shopGroup(String column, Map<String, ItemList> known) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!notBlank(column)) {
            return out;
        }
        for (String code : column.trim().split("\\s+")) {
            if (code.isBlank()) {
                continue;
            }
            ItemList it = known.get(code.toLowerCase());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", code);
            e.put("itemId", it == null ? null : it.getId());
            e.put("idcode", it == null ? null : it.getIdCode());
            e.put("name", it == null ? null : it.getName());
            out.add(e);
        }
        return out;
    }

    /**
     * 保存商店清单：请求体键 = **数据库列名**（`weaponshop` / `defenseshop` / `miscshop`），值 = 物品码数组。
     * 没出现的键**不动**；给出的键整体替换（顺序即游戏内商店顺序，故按客户端给的顺序原样写回）。
     *
     * <p>
     * 校验（不过一律 400，且**一行都不写**）：空物品码、同一列里码重复、三个键都没有。
     * 物品码在 itemlist 里找不到**不拒绝**（既有数据的常态），进 `warnings` 回传。
     *
     * @return null 表示 NPC 不存在；否则为保存后的 {@link #shops} 视图 + `warnings`/`touched`
     */
    @Transactional
    public Map<String, Object> saveShops(int id, Map<String, Object> body) {
        NpcList n = npcListMapper.selectById(id);
        if (n == null) {
            return null;
        }
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("请求体为空：至少给一个商店列（" + String.join(" / ", SHOP_COLUMNS) + "）");
        }
        List<String> warnings = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        NpcList patch = new NpcList();
        patch.setId(id);

        for (String column : SHOP_COLUMNS) {
            if (!body.containsKey(column)) {
                continue;
            }
            Object raw = body.get(column);
            if (!(raw instanceof List<?> list)) {
                throw new IllegalArgumentException("列 " + column + " 需要物品码数组，收到 " + raw);
            }
            List<String> codes = new ArrayList<>();
            for (Object o : list) {
                String code = JsonValues.toStr(o);
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("列 " + column + " 里有空的物品码");
                }
                String c = code.trim();
                for (String seen : codes) {
                    if (seen.equalsIgnoreCase(c)) {
                        throw new IllegalArgumentException("列 " + column + " 里物品码重复：" + c);
                    }
                }
                codes.add(c);
            }
            Map<String, ItemList> known = itemCodeLookup.byCode(codes);
            for (String c : codes) {
                if (!known.containsKey(c.toLowerCase())) {
                    warnings.add("列 " + column + "：物品码 " + c + " 在 itemlist 里找不到，游戏会忽略它");
                }
            }
            // ⚠ **语义没变就不写**：库里这些列有的是 NULL、有的带多余空白（实测语料里两种都有），
            //   无条件写回会把 NULL 变成空串、把空白规范化 —— 一次"没改任何东西的保存"也会动数据。
            //   比较按**大小写不敏感**（游戏匹配码就是不分大小写的，见 LootService/NpcShopService），
            //   于是"只把大小写规范化"不算改动。
            List<String> current = tokensByColumn(n, column);
            if (sameCodes(current, codes)) {
                continue;
            }
            setShopColumn(patch, column, String.join(" ", codes));
            touched.add(column);
        }
        if (touched.isEmpty()) {
            // 三列都给了但都没变：这是一次**无改动保存**，不是错误 —— 回一份 unchanged，且一行都不写
            log.info("[NpcAdmin] 保存商店 NPC id={}：三个商店列都没有变化，未写库", id);
            Map<String, Object> same = shops(id);
            same.put("touched", List.of());
            same.put("unchanged", true);
            same.put("warnings", warnings);
            return same;
        }
        npcListMapper.updateById(patch);
        log.info("[NpcAdmin] 保存商店 NPC id={} 列={}；告警 {} 条：{}", id, touched, warnings.size(), warnings);

        Map<String, Object> out = shops(id);
        out.put("touched", touched);
        out.put("unchanged", false);
        out.put("warnings", warnings);
        return out;
    }

    private static void setShopColumn(NpcList patch, String column, String value) {
        switch (column) {
            case "weaponshop" -> patch.setWeaponShop(value);
            case "defenseshop" -> patch.setDefenseShop(value);
            case "miscshop" -> patch.setMiscShop(value);
            default -> throw new IllegalStateException("不是商店列：" + column);
        }
    }

    // ------------------------------------------------------------------
    // 摆放（mapnpc，只读）
    // ------------------------------------------------------------------

    /**
     * 这个 NPC 站在哪几张图、哪些点（`mapnpc`）。
     *
     * @return null 表示 NPC 不存在；否则 `{count, placements:[{placeId, mapId, mapName, shortName,
     *         x, y, z, angle, enabled, onlyGm}]}`
     */
    public Map<String, Object> places(int id) {
        NpcList n = npcListMapper.selectById(id);
        if (n == null) {
            return null;
        }
        Map<Integer, MapList> mapsById = new LinkedHashMap<>();
        for (MapList l : mapListMapper.selectList(null)) {
            mapsById.put(l.getId(), l);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> seenMaps = new LinkedHashSet<>();
        for (MapNpc p : mapNpcMapper.selectList(
                new QueryWrapper<MapNpc>().eq("idnpc", id).orderByAsc("stage").orderByAsc("id"))) {
            MapList map = p.getStage() == null ? null : mapsById.get(p.getStage());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("placeId", p.getId());
            row.put("mapId", p.getStage());
            row.put("mapName", map == null ? null : map.getName());
            row.put("shortName", map == null ? null : map.getShortName());
            row.put("x", p.getX());
            row.put("y", p.getY());
            row.put("z", p.getZ());
            row.put("angle", p.getAngle());
            row.put("enabled", p.getEnabled());
            row.put("onlyGm", p.getOnlyGm());
            out.add(row);
            if (p.getStage() != null) {
                seenMaps.add(p.getStage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("mapCount", seenMaps.size());
        result.put("placements", out);
        return result;
    }
}
