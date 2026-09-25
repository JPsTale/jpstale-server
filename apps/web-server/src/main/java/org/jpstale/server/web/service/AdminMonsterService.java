package org.jpstale.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.LootService;
import org.jpstale.dao.gamedb.entity.DropItem;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.entity.MapList;
import org.jpstale.dao.gamedb.entity.MapMonster;
import org.jpstale.dao.gamedb.entity.MonsterList;
import org.jpstale.dao.gamedb.mapper.DropItemMapper;
import org.jpstale.dao.gamedb.mapper.MapListMapper;
import org.jpstale.dao.gamedb.mapper.MapMonsterMapper;
import org.jpstale.dao.gamedb.mapper.MonsterListMapper;
import org.jpstale.server.web.admin.AdminEntityService;
import org.jpstale.server.web.admin.ColumnRegistry;
import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.JsonValues;
import org.jpstale.server.web.monster.MonsterColumnRegistry;
import org.jpstale.server.web.monster.MonsterColumnSemantics;
import org.jpstale.server.web.monster.MonsterQueryParams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 怪物管理（`gamedb.monsterlist`）：列表 / 详情 / 修改 + **刷怪地图** + **掉落表**。
 *
 * <p>
 * 列表 / 详情 / 修改的通用部分在 {@link AdminEntityService}；本类给怪物特有的：
 * 列注册表、四组固定筛选、以及两个跨表视图。
 *
 * <p>
 * 两张跨表视图各自的口径（都是**数据实测**定下来的，见 `docs/plans/2026-09-21-pt-web-admin-monsters-design.md`）：
 * <ul>
 *   <li>**刷怪地图**：`mapmonster.stage`（= `maplist.id`）的 `monster1..12` 里按**名字精确匹配**
 *       —— 与刷怪代码同一口径（{@code MapManager.addWave} → {@code MonsterSpawnService}
 *       按名字查模板）。用大小写不敏感匹配会给出游戏里**不会发生**的刷怪，等于谎报。</li>
 *   <li>**掉落表**：键是 `dropitem.dropid` = **`monsterlist.monsterid`**（业务 id，不是主键 id；
 *       全库 2268 行里 2203 行按 monsterid 命中、300 只怪有表，按主键只有 347 行 / 52 只怪）。
 *       `chance` 是**权重**，某行概率 = 权重 / 该 dropid 的权重总和 —— 总和与"哪几行会被跳过"
 *       一律用游戏**同一份实现**（{@link LootService#buildTables}，纯静态）算，
 *       否则页面上的百分比会与游戏里的实际概率不一致，而那正是最容易骗人的地方。</li>
 * </ul>
 *
 * <p>
 * ⚠ `mapmonster` 还有 `bossmonster1..3` / `submonster1..3`（每列 30 个非空值），
 * 但**当前刷怪代码只读 `monster1..12`**（{@code MapManager} 只建那 12 个 wave）。
 * 本服务把 Boss/副怪单独作为 `bossMaps` 返回（不混进 `maps`），界面上另行标明 ——
 * 既不全盘忽略（那是藏数据），也不假装它们真的会刷（那是谎报）。
 */
@Slf4j
@Service
public class AdminMonsterService extends AdminEntityService<MonsterList, MonsterQueryParams> {

    /** `mapmonster` 一行里的普通怪槽位数（`monster1..12`）。 */
    private static final int WAVE_SLOTS = 12;
    /** `mapmonster` 一行里的 Boss / 副怪槽位数（`bossmonster1..3` / `submonster1..3`）。 */
    private static final int BOSS_SLOTS = 3;

    private final MonsterListMapper monsterListMapper;
    private final MapMonsterMapper mapMonsterMapper;
    private final MapListMapper mapListMapper;
    private final DropItemMapper dropItemMapper;
    private final ItemCodeLookup itemCodeLookup;

    public AdminMonsterService(MonsterListMapper monsterListMapper, MapMonsterMapper mapMonsterMapper,
                              MapListMapper mapListMapper, DropItemMapper dropItemMapper,
                              ItemCodeLookup itemCodeLookup) {
        this.monsterListMapper = monsterListMapper;
        this.mapMonsterMapper = mapMonsterMapper;
        this.mapListMapper = mapListMapper;
        this.dropItemMapper = dropItemMapper;
        this.itemCodeLookup = itemCodeLookup;
    }

    // ------------------------------------------------------------------
    // 通用读写（列注册表 / 筛选 / 语义）
    // ------------------------------------------------------------------

    @Override
    protected ColumnRegistry registry() {
        return MonsterColumnRegistry.REGISTRY;
    }

    @Override
    protected BaseMapper<MonsterList> mapper() {
        return monsterListMapper;
    }

    @Override
    protected MonsterList newPatch() {
        return new MonsterList();
    }

    @Override
    protected ColumnSemantics.Semantics semanticsOf(String column) {
        return MonsterColumnSemantics.of(column);
    }

    @Override
    protected String logTag() {
        return "[MonsterAdmin]";
    }

    @Override
    protected String entityLabel() {
        return "怪物定义";
    }

    @Override
    protected void applyFilters(QueryWrapper<MonsterList> w, MonsterQueryParams q) {
        if (q.getNameLike() != null) {
            // 大小写不敏感（ILIKE）+ 通配符转义：与物品列表同一口径（见 AdminItemService）
            w.apply("name ILIKE {0}", "%" + escapeLike(q.getNameLike()) + "%");
        }
        range(w, "level", q.getLevelMin(), q.getLevelMax());
        // 本性 / 属性：数据库原值、精确匹配（忠于数据库：库里 `good` 与 `Good` 并存，不归并）
        if (q.getMonsterType() != null) {
            w.eq("monstertype", q.getMonsterType());
        }
        if (q.getPropertyMon() != null) {
            w.eq("propertymon", q.getPropertyMon());
        }
        if (q.getMap() != null) {
            List<Integer> ids = spawnedOnMap(q.getMap());
            if (ids.isEmpty()) {
                // 该图没有配置任何怪 ⇒ 结果必然为空。
                // 不能用 `IN ()`（MyBatis-Plus 会生成非法 SQL），也不能"跳过条件"（那会静默返回全表）。
                w.apply("1 = 0");
            } else {
                w.in("id", ids);
            }
        }
    }

    // ------------------------------------------------------------------
    // 筛选候选项
    // ------------------------------------------------------------------

    /**
     * 列表筛选用的取值清单：本性 / 属性（**原值 + 计数**）+ 有刷怪配置的地图。
     *
     * <p>
     * 为什么要计数且不归并：与物品 `category` 同理 —— `monstertype` 里存在 `Good` 与 `good`
     * 两个取值（数据实测），归并成一个选项就与数据库不一致了。
     */
    public Map<String, Object> facets() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", facetCounts("monstertype"));
        out.put("properties", facetCounts("propertymon"));

        // 地图候选：只给**有刷怪配置**的图（48 张）—— 没配置的图在这个筛选下恒为空集
        Set<Integer> configured = new LinkedHashSet<>();
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            Integer stage = stageOf(mm);
            if (stage != null) {
                configured.add(stage);
            }
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (MapList l : mapListMapper.selectList(new QueryWrapper<MapList>().orderByAsc("id"))) {
            if (!configured.contains(l.getId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", l.getId());
            m.put("name", l.getName());
            m.put("shortName", l.getShortName());
            maps.add(m);
        }
        out.put("maps", maps);
        return out;
    }

    /** 一列的"原值 + 计数"（按计数降序）。 */
    private List<Map<String, Object>> facetCounts(String column) {
        List<Map<String, Object>> rows = monsterListMapper.selectMaps(
                new QueryWrapper<MonsterList>()
                        .select(column, "count(*) as cnt")
                        .groupBy(column)
                        .orderByDesc("cnt"));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", r.get(column));
            item.put("count", r.get("cnt"));
            out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 刷怪地图
    // ------------------------------------------------------------------

    /**
     * 这只怪出现在哪些地图上。
     *
     * @return null 表示怪物不存在；否则 `{name, monsterId, maps:[…], bossMaps:[…]}`
     *         （`maps` 来自会真的刷怪的 `monster1..12`；`bossMaps` 来自当前刷怪代码**未消费**的
     *         `bossmonster*`/`submonster*` 列）
     */
    public Map<String, Object> spawnMaps(int id) {
        MonsterList m = monsterListMapper.selectById(id);
        if (m == null) {
            return null;
        }
        Integer monsterId = m.getId();      // 槽位存的就是 monsterlist.id（外键）
        Map<Integer, MapList> mapsById = mapsById();

        List<Map<String, Object>> maps = new ArrayList<>();
        List<Map<String, Object>> bossMaps = new ArrayList<>();
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            if (monsterId != null && matchWaveSlot(mm, monsterId)) {
                addMapRef(maps, stageOf(mm), mapsById);
                continue;
            }
            if (monsterId != null && matchBossSlot(mm, monsterId)) {
                addMapRef(bossMaps, stageOf(mm), mapsById);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", m.getName());
        out.put("monsterId", m.getMonsterId());
        out.put("maps", maps);
        out.put("bossMaps", bossMaps);
        return out;
    }

    /** 该行的普通怪槽位里是否有这个名字 —— 与 `MapManager.addWave` 同一判据（**精确**字符串比较）。 */
    private static boolean matchWaveSlot(MapMonster mm, Integer monsterId) {
        for (int i = 1; i <= WAVE_SLOTS; i++) {
            if (monsterId.equals(waveSlot(mm, i))) {
                return true;
            }
        }
        return false;
    }

    /** `bossmonster1..3` / `submonster1..3` 里是否有该名字。 */
    private static boolean matchBossSlot(MapMonster mm, Integer monsterId) {
        for (int i = 1; i <= BOSS_SLOTS; i++) {
            if (monsterId.equals(bossSlot(mm, i)) || monsterId.equals(subSlot(mm, i))) {
                return true;
            }
        }
        return false;
    }

    private static Integer waveSlot(MapMonster mm, int i) {
        return switch (i) {
            case 1 -> mm.getMonster1();
            case 2 -> mm.getMonster2();
            case 3 -> mm.getMonster3();
            case 4 -> mm.getMonster4();
            case 5 -> mm.getMonster5();
            case 6 -> mm.getMonster6();
            case 7 -> mm.getMonster7();
            case 8 -> mm.getMonster8();
            case 9 -> mm.getMonster9();
            case 10 -> mm.getMonster10();
            case 11 -> mm.getMonster11();
            case 12 -> mm.getMonster12();
            default -> null;
        };
    }

    private static Integer bossSlot(MapMonster mm, int i) {
        return switch (i) {
            case 1 -> mm.getBossMonster1();
            case 2 -> mm.getBossMonster2();
            case 3 -> mm.getBossMonster3();
            default -> null;
        };
    }

    private static Integer subSlot(MapMonster mm, int i) {
        return switch (i) {
            case 1 -> mm.getSubMonster1();
            case 2 -> mm.getSubMonster2();
            case 3 -> mm.getSubMonster3();
            default -> null;
        };
    }

    /** 该图会刷的怪 id（`monster1..12` 去重）——"所在地图"筛选与 `spawnMaps` 同一判据。 */
    private List<Integer> spawnedOnMap(int mapId) {
        List<Integer> ids = new ArrayList<>();
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            if (!Integer.valueOf(mapId).equals(stageOf(mm))) {
                continue;
            }
            for (int i = 1; i <= WAVE_SLOTS; i++) {
                Integer n = waveSlot(mm, i);
                if (n != null && n > 0 && !ids.contains(n)) {
                    ids.add(n);
                }
            }
        }
        return ids;
    }

    private void addMapRef(List<Map<String, Object>> out, Integer mapId, Map<Integer, MapList> mapsById) {
        if (mapId == null) {
            return;
        }
        for (Map<String, Object> existing : out) {
            if (mapId.equals(existing.get("mapId"))) {
                return;     // 同一张图只列一次
            }
        }
        MapList l = mapsById.get(mapId);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("mapId", mapId);
        // 图名拿不到就只给 id（不编名字）—— 实测 48 个 stage 全部能对上 maplist
        ref.put("name", l == null ? null : l.getName());
        ref.put("shortName", l == null ? null : l.getShortName());
        out.add(ref);
    }

    private Map<Integer, MapList> mapsById() {
        Map<Integer, MapList> byId = new LinkedHashMap<>();
        for (MapList l : mapListMapper.selectList(null)) {
            byId.put(l.getId(), l);
        }
        return byId;
    }

    /** `mapmonster.stage` 是**字符串**形式的 `maplist.id`；不是数字就给 null（不猜）。 */
    private static Integer stageOf(MapMonster mm) {
        String s = mm.getStage();
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 掉落表
    // ------------------------------------------------------------------

    /**
     * 这只怪的掉落表（键 = `monsterlist.monsterid`）。
     *
     * @return null 表示怪物不存在；否则 `{dropId, totalChance, rows:[…]}`
     *         （每行给出 `percent` = 权重/总权重×100，以及 `skipped`/`skipReason` ——
     *         游戏会跳过的行（`chance<=0`、或整行的物品码一个都认不出）照样列出但标出来）
     */
    public Map<String, Object> drops(int id) {
        MonsterList m = monsterListMapper.selectById(id);
        if (m == null) {
            return null;
        }
        int dropId = m.getMonsterId() == null ? -1 : m.getMonsterId();
        List<DropItem> rows = dropItemMapper.selectList(
                new QueryWrapper<DropItem>().eq("dropid", dropId).orderByAsc("id"));

        // 物品码 → 物品（共用解析器；大小写不敏感，与 LootService 建表时的口径一致）
        Map<String, ItemList> codeToItem = itemCodeLookup.byItemsStrings(
                rows.stream().map(DropItem::getItems).toList());
        Map<String, Integer> codeToIdCode = new HashMap<>();
        codeToItem.forEach((code, it) -> {
            if (it.getIdCode() != null) {
                codeToIdCode.put(code, it.getIdCode());
            }
        });

        // ⚠ 总权重与"哪些行会被跳过"用**游戏同一份实现**（纯静态，不触发 LootService 的扫表 bean）
        LootService.DropTable table = LootService.buildTables(rows, codeToIdCode).get(dropId);
        int total = table == null ? 0 : table.totalChance;

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (DropItem d : rows) {
            String items = d.getItems() == null ? "" : d.getItems().trim();
            int chance = d.getChance() == null ? 0 : d.getChance();
            String kind = dropKind(items);

            List<Map<String, Object>> entries = new ArrayList<>();
            for (String tok : tokens(items)) {
                if (kind.equals("GOLD") || kind.equals("AIR")) {
                    break;
                }
                ItemList it = codeToItem.get(tok.toLowerCase());
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("code", tok);
                // id = **itemlist 主键**：跨页引用一律传主键（一个 codeimg1 可能对应多行，只有主键唯一定位）
                e.put("itemId", it == null ? null : it.getId());
                e.put("idcode", it == null ? null : it.getIdCode());
                e.put("name", it == null ? null : it.getName());
                entries.add(e);
            }
            // 与 LootService.buildTables 的跳过规则一致：权重<=0；物品行整行的码都认不出
            String skipReason = chance <= 0 ? "chanceZero"
                    : (kind.equals("ITEMS") && entries.isEmpty()) ? "unknownItemCodes" : null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("dropId", d.getDropId());
            row.put("kind", kind);
            row.put("items", items);
            row.put("chance", chance);
            row.put("percent", total > 0 ? Math.round(chance * 10000.0 / total) / 100.0 : null);
            row.put("goldMin", d.getGoldMin());
            row.put("goldMax", d.getGoldMax());
            row.put("entries", entries);
            row.put("skipped", skipReason != null);
            row.put("skipReason", skipReason);
            out.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dropId", dropId);
        result.put("totalChance", total);
        result.put("rowCount", out.size());
        result.put("rows", out);
        return result;
    }

    // ------------------------------------------------------------------
    // Boss 名单（列表徽章用）
    // ------------------------------------------------------------------

    /**
     * 在 `mapmonster` 的 `bossmonster1..3` / `submonster1..3` 列里出现过的怪名（实测 52 个，全部能对上
     * `monsterlist.name`）。
     *
     * <p>
     * ⚠ 这是**表里声明的 Boss**，不是"会刷的 Boss"：当前刷怪代码只读 `monster1..12`
     * （见 {@link #spawnMaps}）。所以它只作界面标注用，文案必须如实说明这一点
     * —— 既不全盘忽略（那是藏数据），也不假装它们真的会刷（那是谎报）。
     */
    public Map<String, Object> bosses() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            for (int i = 1; i <= BOSS_SLOTS; i++) {
                addId(ids, bossSlot(mm, i));
                addId(ids, subSlot(mm, i));
            }
        }
        // 响应形状保持 {names: [...]}（JS 徽标直接显示）—— 槽位是 id ⇒ 解析成 monsterlist 的显示名
        Map<Integer, String> nameById = new LinkedHashMap<>();
        for (MonsterList m : monsterListMapper.selectList(null)) {
            if (m.getId() != null) {
                nameById.put(m.getId(), m.getName());
            }
        }
        List<String> names = ids.stream().map(id -> nameById.getOrDefault(id, "#" + id)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("names", names);
        return out;
    }

    private static void addId(Set<Integer> out, Integer id) {
        if (id != null && id > 0) {
            out.add(id);
        }
    }

    private static void addName(Set<String> out, String name) {
        if (name != null && !name.isBlank()) {
            out.add(name);
        }
    }

    // ------------------------------------------------------------------
    // 掉落表：保存（整表替换）
    // ------------------------------------------------------------------

    /**
     * 保存这只怪的掉落表 —— 请求体里的 `rows` 就是**保存后的全部行**。
     *
     * <p>
     * 为什么是"整表替换"而不是逐行增/删/改三个接口：编辑一张 15 行以内的表时，界面上就是
     * "改几条、加一条、删一条"混在一起做的；拆成三种请求会让"保存"变成非原子的多步，
     * 中途失败就留下半张表。这里在**一个事务**里 diff 出 INSERT/UPDATE/DELETE
     * （`dropitem` 只有主键约束，无 `(dropid,items)` 唯一键，故不需要 `userdb.item` 那套停车逻辑）。
     *
     * <p>
     * ⚠ **安全性质**（数据实测）：`dropid` == `monsterlist.monsterid` 且 `monsterid` 唯一，
     * 304 个 dropid **没有一个**被两只怪共用 ⇒ 这里只会动到**这一只怪**的行，不可能波及别的怪。
     * （某些管理系统为此要"另建一个专属 DropID"，我们不需要 —— 数据模型本身就隔离。）
     *
     * <p>
     * 校验（不过一律 {@link IllegalArgumentException} → 400，且整表不落地）：
     * 空 items、负数权重、引用了别只怪的行 id、同一 id 重复出现、金币区间倒置、负数金币。
     * 物品码在 itemlist 里找不到**不拒绝**（那是既有数据的常态），只进 `warnings` 回传给界面。
     *
     * @return null 表示怪物不存在；否则是保存后的掉落视图（与 {@link #drops} 同形）
     *         + `added`/`updated`/`removed`/`warnings`
     */
    @Transactional
    public Map<String, Object> saveDrops(int id, List<Map<String, Object>> rows) {
        MonsterList m = monsterListMapper.selectById(id);
        if (m == null) {
            return null;
        }
        Integer monsterId = m.getMonsterId();
        if (monsterId == null || monsterId <= 0) {
            throw new IllegalArgumentException("这只怪的 monsterid 无效（" + monsterId + "），无法保存掉落");
        }
        if (rows == null) {
            throw new IllegalArgumentException("请求体缺少 rows（应为保存后的全部掉落行）");
        }

        // 物品码 → 物品（用于"认不出"的告警；与读取路径同一份实现）
        List<DropItem> probe = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            DropItem p = new DropItem();
            p.setItems(JsonValues.toStr(r.get("items")));
            probe.add(p);
        }
        Map<String, ItemList> known = itemCodeLookup.byItemsStrings(
                probe.stream().map(DropItem::getItems).toList());

        Map<Integer, DropItem> existingById = new LinkedHashMap<>();
        for (DropItem d : dropItemMapper.selectList(new QueryWrapper<DropItem>().eq("dropid", monsterId))) {
            existingById.put(d.getId(), d);
        }

        List<String> warnings = new ArrayList<>();
        List<DropItem> desired = new ArrayList<>();
        Set<Integer> touched = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            String at = "第 " + (i + 1) + " 条";
            String items = JsonValues.toStr(r.get("items"));
            if (items == null || items.isBlank()) {
                throw new IllegalArgumentException(at + "：items 不能为空（物品行写物品码、金币行写 Gold、空行写 Air）");
            }
            items = items.trim();
            Integer chance = JsonValues.toInt(r.get("chance"), at + " 权重");
            if (chance == null) {
                throw new IllegalArgumentException(at + "：缺少权重 chance");
            }
            if (chance < 0) {
                throw new IllegalArgumentException(at + "：权重不能为负（" + chance + "）");
            }
            String kind = dropKind(items);
            Integer goldMin = JsonValues.toInt(r.get("goldMin"), at + " goldMin");
            Integer goldMax = JsonValues.toInt(r.get("goldMax"), at + " goldMax");
            if (goldMin != null && goldMin < 0 || goldMax != null && goldMax < 0) {
                throw new IllegalArgumentException(at + "：金币不能为负");
            }
            if (goldMin != null && goldMax != null && goldMin > goldMax) {
                throw new IllegalArgumentException(at + "：金币区间倒置（" + goldMin + " > " + goldMax + "）");
            }
            if (kind.equals("ITEMS")) {
                List<String> codes = tokens(items);
                if (codes.isEmpty()) {
                    throw new IllegalArgumentException(at + "：物品行至少要有一个物品码");
                }
                for (String c : codes) {
                    if (!known.containsKey(c.toLowerCase())) {
                        warnings.add(at + "：物品码 " + c + " 在 itemlist 里找不到，游戏会忽略它");
                    }
                }
            }

            DropItem d = new DropItem();
            d.setDropId(monsterId);
            d.setItems(items);
            d.setChance(chance);
            // 金币只对 Gold 行有意义；其余行按库里既有约定写 0（不是 null —— 实测语料全是 0）
            d.setGoldMin(kind.equals("GOLD") && goldMin != null ? goldMin : 0);
            d.setGoldMax(kind.equals("GOLD") && goldMax != null ? goldMax : 0);

            Integer rowId = JsonValues.toInt(r.get("id"), at + " id");
            if (rowId == null) {
                desired.add(d);                     // 新增
                continue;
            }
            if (!existingById.containsKey(rowId)) {
                throw new IllegalArgumentException(at + "：id=" + rowId + " 不属于这只怪（dropid=" + monsterId + "）的掉落表");
            }
            if (!touched.add(rowId)) {
                throw new IllegalArgumentException(at + "：id=" + rowId + " 重复出现");
            }
            d.setId(rowId);
            desired.add(d);                         // 修改
        }

        int removed = 0;
        for (Integer existingId : existingById.keySet()) {
            if (!touched.contains(existingId)) {
                dropItemMapper.deleteById(existingId);
                removed++;
            }
        }
        int added = 0;
        int updated = 0;
        for (DropItem d : desired) {
            if (d.getId() == null) {
                dropItemMapper.insert(d);
                added++;
            } else {
                dropItemMapper.updateById(d);
                updated++;
            }
        }
        log.info("[MonsterAdmin] 保存掉落 怪物id={}（dropid={}）新增 {} 改 {} 删 {}；告警 {} 条：{}",
                id, monsterId, added, updated, removed, warnings.size(), warnings);

        Map<String, Object> out = drops(id);
        out.put("added", added);
        out.put("updated", updated);
        out.put("removed", removed);
        out.put("warnings", warnings);
        return out;
    }

    /** 掉落行类型：`Gold` / `Air` / 物品行（大小写不敏感，与 {@code LootService} 同一判据）。 */
    private static String dropKind(String items) {
        return items.equalsIgnoreCase("Gold") ? "GOLD" : items.equalsIgnoreCase("Air") ? "AIR" : "ITEMS";
    }

    /** 按空白切出物品码（与 {@code LootService.buildTables} 同一口径）。 */
    private static List<String> tokens(String items) {
        List<String> out = new ArrayList<>();
        for (String tok : items.split("\\s+")) {
            if (!tok.isBlank()) {
                out.add(tok);
            }
        }
        return out;
    }
}
