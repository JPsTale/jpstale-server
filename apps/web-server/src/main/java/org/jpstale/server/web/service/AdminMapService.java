package org.jpstale.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.MapList;
import org.jpstale.dao.gamedb.entity.MapMonster;
import org.jpstale.dao.gamedb.entity.MapNpc;
import org.jpstale.dao.gamedb.entity.MapSpawnPoint;
import org.jpstale.dao.gamedb.entity.MonsterList;
import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.dao.gamedb.mapper.MapListMapper;
import org.jpstale.dao.gamedb.mapper.MapMonsterMapper;
import org.jpstale.dao.gamedb.mapper.MapNpcMapper;
import org.jpstale.dao.gamedb.mapper.MapSpawnPointMapper;
import org.jpstale.dao.gamedb.mapper.MonsterListMapper;
import org.jpstale.dao.gamedb.mapper.NpcListMapper;
import org.jpstale.server.web.admin.AdminEntityService;
import org.jpstale.server.web.admin.ColumnRegistry;
import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.JsonValues;
import org.jpstale.server.web.map.MapColumnRegistry;
import org.jpstale.server.web.map.MapColumnSemantics;
import org.jpstale.server.web.map.MapQueryParams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * 管理端：地图（`gamedb.maplist`）—— 详情页是四张表的**汇聚点**，本期**只读**。
 *
 * <p>
 * 除 maplist 自身字段外，地图详情聚合三段（用户 2026-09-22 选的"汇聚点只读版"）：
 * <ul>
 *   <li>**刷怪配置**（`mapmonster`，一行一张图）：`monster1..12`/`count1..12` 是"刷什么、权重多少"，
 *       外加 `maxmonsters`/`interval` 与 `bossmonster1..3`/`submonster1..3`（后两组**当前刷怪代码不读**，
 *       与怪物详情里的 Boss 段同一口径：列出来但标明）；</li>
 *   <li>**NPC**（`mapnpc`，`idnpc` → `npclist.id`）：位置 x/y/z/angle + `enabled`/`onlygm`；</li>
 *   <li>**刷新点**（`mapspawnpoint`）：x/z + description（实测每图 1~149 个）。</li>
 * </ul>
 *
 * <p>
 * ⚠ 列表仍走 {@link #listAll()}（返回 {@code AdminMapSummary}，前端过滤那版页面的形状）；
 * 详情走基类的 `getById`（**列名键的行**，与物品/怪物/NPC 一致）。两条读取路径互不影响，
 * 但 /{id} 的形状在 2026-09-22 由 summary 改成了列名键的行（只有详情页用它）。
 */
@Slf4j
@Service
public class AdminMapService extends AdminEntityService<MapList, MapQueryParams> {

    /** `mapmonster` 一行里的普通怪槽位数（`monster1..12`）。 */
    private static final int WAVE_SLOTS = 12;
    /** `mapmonster` 一行里的 Boss / 副怪槽位数。 */
    private static final int BOSS_SLOTS = 3;

    /**
     * `mapspawnpoint.id` 在本库**没有序列/默认值**（实测：其它 5 张 map/item/monster/npc 表都有
     * `*_id_seq`，只有它没有 —— 导入时留下的 schema 缺口）。
     *
     * <p>
     * 于是新增刷新点必须由应用分配 id。这里**先探一次库**：有默认值就交给库（将来补了序列会自动走序列，
     * 不会与手分配撞号）；没有就 `max(id)+1`（单管理员场景，够用）。
     * 探测结果与处置都**写日志**——不静默。
     */
    private volatile Boolean spawnPointIdHasDefault = null;

    private final javax.sql.DataSource dataSource;

    private final MapListMapper mapListMapper;
    private final MapMonsterMapper mapMonsterMapper;
    private final MapNpcMapper mapNpcMapper;
    private final MapSpawnPointMapper mapSpawnPointMapper;
    private final MonsterListMapper monsterListMapper;
    private final NpcListMapper npcListMapper;

    public AdminMapService(javax.sql.DataSource dataSource,
                           MapListMapper mapListMapper, MapMonsterMapper mapMonsterMapper,
                           MapNpcMapper mapNpcMapper, MapSpawnPointMapper mapSpawnPointMapper,
                           MonsterListMapper monsterListMapper, NpcListMapper npcListMapper) {
        this.dataSource = dataSource;
        this.mapListMapper = mapListMapper;
        this.mapMonsterMapper = mapMonsterMapper;
        this.mapNpcMapper = mapNpcMapper;
        this.mapSpawnPointMapper = mapSpawnPointMapper;
        this.monsterListMapper = monsterListMapper;
        this.npcListMapper = npcListMapper;
    }

    // ------------------------------------------------------------------
    // 基类接线（读：列清单 / 详情行；写本期不暴露）
    // ------------------------------------------------------------------

    @Override
    protected ColumnRegistry registry() {
        return MapColumnRegistry.REGISTRY;
    }

    @Override
    protected BaseMapper<MapList> mapper() {
        return mapListMapper;
    }

    @Override
    protected MapList newPatch() {
        return new MapList();
    }

    @Override
    protected ColumnSemantics.Semantics semanticsOf(String column) {
        return MapColumnSemantics.of(column);
    }

    @Override
    protected String logTag() {
        return "[MapAdmin]";
    }

    @Override
    protected String entityLabel() {
        return "地图定义";
    }

    @Override
    protected void applyFilters(QueryWrapper<MapList> w, MapQueryParams q) {
        if (q.getNameLike() != null) {
            // 一个输入框同时搜 name 与 shortname（旧版页面就是"按名称 / shortName 搜索"）
            String like = "%" + escapeLike(q.getNameLike()) + "%";
            w.apply("(name ILIKE {0} OR shortname ILIKE {0})", like);
        }
        if (q.getTypeMap() != null) {
            w.eq("typemap", q.getTypeMap());
        }
        range(w, "levelreq", q.getLevelMin(), q.getLevelMax());
        if (q.getPvp() != null) {
            w.eq("pvp", q.getPvp());
        }
        if (q.getHasSpawn() != null) {
            List<Integer> ids = spawnConfiguredMapIds();
            if (ids.isEmpty()) {
                // 一张都没有配置：要"有配置"的是空集；要"没配置"的就是全部
                w.apply(q.getHasSpawn() ? "1 = 0" : "1 = 1");
            } else if (q.getHasSpawn()) {
                w.in("id", ids);
            } else {
                w.notIn("id", ids);
            }
        }
    }

    /** `mapmonster` 里有行的地图 id（实测 48 张）—— 与 {@link #spawn} 的 configured 是同一判据。 */
    private List<Integer> spawnConfiguredMapIds() {
        List<Integer> ids = new ArrayList<>();
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            if (mm.getStage() == null) {
                continue;
            }
            try {
                Integer id = Integer.valueOf(mm.getStage().trim());
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // stage 不是数字：这条配置不指向任何图（实测 0 条）
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 筛选候选项
    // ------------------------------------------------------------------

    /** 筛选候选项：地形类型（**原文 + 计数**，13 种）。 */
    public Map<String, Object> facets() {
        List<Map<String, Object>> rows = mapListMapper.selectMaps(
                new QueryWrapper<MapList>()
                        .select("typemap", "count(*) as cnt")
                        .groupBy("typemap")
                        .orderByDesc("cnt"));
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("value", r.get("typemap"));
            t.put("count", r.get("cnt"));
            types.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("typeMaps", types);
        return out;
    }

    // ------------------------------------------------------------------
    // 刷怪配置：保存（mapmonster 一行）
    // ------------------------------------------------------------------

    /**
     * 保存这张图的刷怪配置 —— 请求体里的 `waves` / `bossWaves` 就是**保存后的全部槽位**。
     *
     * <p>
     * 载荷（键与库里的列族对应）：
     * <pre>
     * { "maxmonsters": 250, "interval": 1,
     *   "waves":     [ {"slot":1, "monster":"Mushroom Ghost", "count":12}, ... ],      // slot 1..12
     *   "bossWaves": [ {"kind":"boss"|"sub", "slot":1, "monster":"Platin Mav"}, ... ] } // slot 1..3
     * </pre>
     *
     * <p>
     * 三条口径（都是实测定的）：
     * <ul>
     *   <li>**空槽位写 NULL，不是空串** —— 语料 48 行全部如此（`monster6` null=16、`monster12` null=48、空串 0）。
     *       ⚠ 因此不能用 `updateById`（MyBatis-Plus **忽略 null 字段**），这里用
     *       `UpdateWrapper.set(列, null)` 显式写 NULL。</li>
     *   <li>**不碰 `hoursbossmonster*` / `countsub*` / `maxenemyflag`** —— 前者存的是"出现小时列表"
     *       （实测 `0 1 2 … 23`），当前刷怪代码**不读**这几列，我们也没有编辑它的界面：不动没搞懂的列。</li>
     *   <li>没配置过的图（15 张，多为城镇）可以**新建一行** —— 保存即 upsert。</li>
     * </ul>
     *
     * 校验（不过一律 400，且**一行都不写**）：slot 越界/重复、数量为负、数组里出现非对象元素。
     * 怪物名在 `monsterlist` 找不到、或数量为 0 **不拒绝但告警**（`MapManager` 确实会跳过它们）。
     *
     * @return null 表示地图不存在；否则为保存后的 {@link #spawn} 视图 + `warnings`/`created`
     */
    @Transactional
    public Map<String, Object> saveSpawn(int id, Map<String, Object> body) {
        if (mapListMapper.selectById(id) == null) {
            return null;
        }
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        // 槽位 = **monsterlist.id**（外键）；存在性校验 = id 是否在 monsterlist 里
        java.util.Set<Integer> monsterIds = monsterListMapper.selectList(null).stream()
                .map(MonsterList::getId).collect(java.util.stream.Collectors.toSet());
        List<String> warnings = new ArrayList<>();

        Integer[] waveIds = new Integer[WAVE_SLOTS];
        Integer[] waveCounts = new Integer[WAVE_SLOTS];
        Integer[] bossIds = new Integer[BOSS_SLOTS];
        Integer[] subIds = new Integer[BOSS_SLOTS];

        Object rawWaves = body.get("waves");
        if (rawWaves != null) {
            if (!(rawWaves instanceof List<?> list)) {
                throw new IllegalArgumentException("waves 需要数组");
            }
            for (Object o : list) {
                Map<?, ?> m = asMap(o, "waves");
                int slot = slotOf(m.get("slot"), WAVE_SLOTS, "waves");
                Integer monsterId = JsonValues.toInt(m.get("monster"), "waves 的 monster");
                if (monsterId == null) {
                    continue;
                }
                if (waveIds[slot - 1] != null) {
                    throw new IllegalArgumentException("monster" + slot + " 出现了两次");
                }
                Integer count = JsonValues.toInt(m.get("count"), "waves 的 count");
                if (count == null) {
                    count = 0;
                }
                if (count < 0) {
                    throw new IllegalArgumentException("monster" + slot + " 的数量不能为负（" + count + "）");
                }
                if (count == 0) {
                    warnings.add("monster" + slot + "（id " + monsterId + "）数量为 0，刷怪代码会跳过它");
                }
                if (!monsterIds.contains(monsterId)) {
                    warnings.add("monster" + slot + "：怪物 id " + monsterId + " 在 monsterlist 里不存在，刷怪代码会跳过它");
                }
                waveIds[slot - 1] = monsterId;
                waveCounts[slot - 1] = count;
            }
        }

        Object rawBoss = body.get("bossWaves");
        if (rawBoss != null) {
            if (!(rawBoss instanceof List<?> list)) {
                throw new IllegalArgumentException("bossWaves 需要数组");
            }
            for (Object o : list) {
                Map<?, ?> m = asMap(o, "bossWaves");
                String kind = JsonValues.toStr(m.get("kind"));
                if (kind == null || !(kind.equals("boss") || kind.equals("sub"))) {
                    throw new IllegalArgumentException("bossWaves 的 kind 只能是 boss 或 sub（收到：" + kind + "）");
                }
                int slot = slotOf(m.get("slot"), BOSS_SLOTS, "bossWaves");
                Integer monsterId = JsonValues.toInt(m.get("monster"), "bossWaves 的 monster");
                if (monsterId == null) {
                    continue;
                }
                Integer[] target = kind.equals("boss") ? bossIds : subIds;
                if (target[slot - 1] != null) {
                    throw new IllegalArgumentException(kind + slot + " 出现了两次");
                }
                if (!monsterIds.contains(monsterId)) {
                    warnings.add(kind + "monster" + slot + "：怪物 id " + monsterId + " 在 monsterlist 里不存在，刷怪代码会跳过它");
                }
                target[slot - 1] = monsterId;
            }
        }

        Integer maxMonsters = JsonValues.toInt(body.get("maxmonsters"), "maxmonsters");
        Integer interval = JsonValues.toInt(body.get("interval"), "interval");
        if (maxMonsters != null && maxMonsters < 0 || interval != null && interval < 0) {
            throw new IllegalArgumentException("maxmonsters / interval 不能为负");
        }

        MapMonster existing = findRow(id);
        boolean created = existing == null;
        if (created) {
            MapMonster row = new MapMonster();
            row.setStage(String.valueOf(id));
            row.setMaxMonsters(maxMonsters == null ? 0 : maxMonsters);
            row.setInterval(interval == null ? 0 : interval);
            for (int i = 0; i < WAVE_SLOTS; i++) {
                setWave(row, i, waveIds[i], waveCounts[i]);
            }
            for (int i = 0; i < BOSS_SLOTS; i++) {
                setBoss(row, i, bossIds[i]);
                setSub(row, i, subIds[i]);
            }
            mapMonsterMapper.insert(row);
        } else {
            UpdateWrapper<MapMonster> u = new UpdateWrapper<>();
            u.eq("id", existing.getId());
            // 显式 set 每一列（含 null）—— updateById 会忽略 null，清空槽位就写不进去
            if (maxMonsters != null) {
                u.set("maxmonsters", maxMonsters);
            }
            if (interval != null) {
                u.set("interval", interval);
            }
            for (int i = 0; i < WAVE_SLOTS; i++) {
                u.set("monster" + (i + 1), waveIds[i]);
                u.set("count" + (i + 1), waveCounts[i]);
            }
            for (int i = 0; i < BOSS_SLOTS; i++) {
                u.set("bossmonster" + (i + 1), bossIds[i]);
                u.set("submonster" + (i + 1), subIds[i]);
            }
            mapMonsterMapper.update(null, u);
        }
        log.info("[MapAdmin] 保存刷怪配置 地图id={}{}；槽位 {} / boss {} / sub {}；告警 {} 条：{}",
                id, created ? "（新建）" : "", countNonBlank(waveIds), countNonBlank(bossIds),
                countNonBlank(subIds), warnings.size(), warnings);

        Map<String, Object> out = spawn(id);
        out.put("created", created);
        out.put("warnings", warnings);
        return out;
    }

    private static Map<?, ?> asMap(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(what + " 里出现非对象元素：" + o);
    }

    private static int slotOf(Object raw, int max, String what) {
        Integer slot = JsonValues.toInt(raw, what + " 的 slot");
        if (slot == null || slot < 1 || slot > max) {
            throw new IllegalArgumentException(what + " 的 slot 必须在 1.." + max + "（收到：" + raw + "）");
        }
        return slot;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int countNonBlank(Integer[] arr) {
        int n = 0;
        for (Integer s : arr) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    private void setWave(MapMonster row, int i, Integer monsterId, Integer count) {
        switch (i) {
            case 0 -> { row.setMonster1(monsterId); row.setCount1(count); }
            case 1 -> { row.setMonster2(monsterId); row.setCount2(count); }
            case 2 -> { row.setMonster3(monsterId); row.setCount3(count); }
            case 3 -> { row.setMonster4(monsterId); row.setCount4(count); }
            case 4 -> { row.setMonster5(monsterId); row.setCount5(count); }
            case 5 -> { row.setMonster6(monsterId); row.setCount6(count); }
            case 6 -> { row.setMonster7(monsterId); row.setCount7(count); }
            case 7 -> { row.setMonster8(monsterId); row.setCount8(count); }
            case 8 -> { row.setMonster9(monsterId); row.setCount9(count); }
            case 9 -> { row.setMonster10(monsterId); row.setCount10(count); }
            case 10 -> { row.setMonster11(monsterId); row.setCount11(count); }
            case 11 -> { row.setMonster12(monsterId); row.setCount12(count); }
            default -> throw new IllegalStateException("wave 槽位越界：" + i);
        }
    }

    private void setBoss(MapMonster row, int i, Integer monsterId) {
        switch (i) {
            case 0 -> row.setBossMonster1(monsterId);
            case 1 -> row.setBossMonster2(monsterId);
            case 2 -> row.setBossMonster3(monsterId);
            default -> throw new IllegalStateException("boss 槽位越界：" + i);
        }
    }

    private void setSub(MapMonster row, int i, Integer monsterId) {
        switch (i) {
            case 0 -> row.setSubMonster1(monsterId);
            case 1 -> row.setSubMonster2(monsterId);
            case 2 -> row.setSubMonster3(monsterId);
            default -> throw new IllegalStateException("sub 槽位越界：" + i);
        }
    }

    /** 取这张图的 `mapmonster` 行（一行一图，实测 48/48）。 */
    private MapMonster findRow(int id) {
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            if (String.valueOf(id).equals(mm.getStage())) {
                return mm;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // NPC 摆放 / 刷新点：保存（整表替换）
    // ------------------------------------------------------------------

    /**
     * 保存这张图的 **NPC 摆放**（`mapnpc`）—— 请求体里的 `npcs` 就是保存后的全部摆放。
     *
     * <p>
     * 载荷：`{"npcs":[{"placeId":12,"npcId":96,"x":-994,"y":179,"z":17348,"angle":5193,
     * "enabled":1,"onlyGm":0}, …]}` —— 带 `placeId` = 改这一行，不带 = 新增，现有行未出现 = 删除。
     *
     * <p>
     * 校验（不过一律 400，且**一行都不写**）：`npcId` 必须是 `npclist` 里存在的（否则刷怪/对话都会落空）、
     * 坐标与角度必须是整数、`enabled`/`onlyGm` 只能是 0/1、`placeId` 必须属于这张图。
     *
     * @return null 表示地图不存在；否则为保存后的 {@link #npcs} 视图 + `added`/`updated`/`removed`
     */
    @Transactional
    public Map<String, Object> saveNpcs(int id, Map<String, Object> body) {
        if (mapListMapper.selectById(id) == null) {
            return null;
        }
        List<Map<String, Object>> rows = rowsOf(body, "npcs");
        Set<Integer> knownNpcIds = new LinkedHashSet<>();
        for (NpcList n : npcListMapper.selectList(null)) {
            knownNpcIds.add(n.getId());
        }
        Map<Integer, MapNpc> existing = new LinkedHashMap<>();
        for (MapNpc p : mapNpcMapper.selectList(new QueryWrapper<MapNpc>().eq("stage", id))) {
            existing.put(p.getId(), p);
        }

        List<MapNpc> desired = new ArrayList<>();
        Set<Integer> touched = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            String at = "第 " + (i + 1) + " 条";
            Integer npcId = JsonValues.toInt(r.get("npcId"), at + " npcId");
            if (npcId == null || !knownNpcIds.contains(npcId)) {
                throw new IllegalArgumentException(at + "：npcId=" + npcId + " 在 npclist 里不存在");
            }
            MapNpc row = new MapNpc();
            row.setStage(id);
            row.setIdNpc(npcId);
            row.setX(requiredInt(r, "x", at));
            row.setY(requiredInt(r, "y", at));
            row.setZ(requiredInt(r, "z", at));
            row.setAngle(r.get("angle") == null ? 0 : requiredInt(r, "angle", at));
            row.setEnabled(flag(r, "enabled", at));
            row.setOnlyGm(flag(r, "onlyGm", at));

            Integer placeId = JsonValues.toInt(r.get("placeId"), at + " placeId");
            if (placeId == null) {
                desired.add(row);                       // 新增
                continue;
            }
            if (!existing.containsKey(placeId)) {
                throw new IllegalArgumentException(at + "：placeId=" + placeId + " 不属于这张图（stage=" + id + "）的摆放");
            }
            if (!touched.add(placeId)) {
                throw new IllegalArgumentException(at + "：placeId=" + placeId + " 重复出现");
            }
            row.setId(placeId);
            desired.add(row);                           // 修改
        }

        int removed = 0;
        for (Integer existingId : existing.keySet()) {
            if (!touched.contains(existingId)) {
                mapNpcMapper.deleteById(existingId);
                removed++;
            }
        }
        int added = 0;
        int updated = 0;
        for (MapNpc row : desired) {
            if (row.getId() == null) {
                mapNpcMapper.insert(row);
                added++;
            } else {
                mapNpcMapper.updateById(row);
                updated++;
            }
        }
        log.info("[MapAdmin] 保存 NPC 摆放 地图id={} 新增 {} 改 {} 删 {}", id, added, updated, removed);

        Map<String, Object> out = npcs(id);
        out.put("added", added);
        out.put("updated", updated);
        out.put("removed", removed);
        return out;
    }

    /**
     * 保存这张图的 **刷新点**（`mapspawnpoint`）—— 请求体里的 `points` 就是保存后的全部刷新点。
     *
     * <p>
     * 载荷：`{"points":[{"pointId":5,"x":2592,"z":18566,"description":"1"}, …]}`（同"整表替换"语义）。
     * `description` 空白写 **NULL**（语料里没描述的点就是 NULL，不是空串）。
     *
     * @return null 表示地图不存在；否则为保存后的 {@link #points} 视图 + `added`/`updated`/`removed`
     */
    @Transactional
    public Map<String, Object> savePoints(int id, Map<String, Object> body) {
        if (mapListMapper.selectById(id) == null) {
            return null;
        }
        List<Map<String, Object>> rows = rowsOf(body, "points");
        Map<Integer, MapSpawnPoint> existing = new LinkedHashMap<>();
        for (MapSpawnPoint p : mapSpawnPointMapper.selectList(
                new QueryWrapper<MapSpawnPoint>().eq("stage", id))) {
            existing.put(p.getId(), p);
        }

        List<MapSpawnPoint> desired = new ArrayList<>();
        Set<Integer> touched = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            String at = "第 " + (i + 1) + " 条";
            MapSpawnPoint row = new MapSpawnPoint();
            row.setStage(id);
            row.setX(requiredInt(r, "x", at));
            row.setZ(requiredInt(r, "z", at));
            String desc = JsonValues.toStr(r.get("description"));
            row.setDescription(desc == null || desc.isBlank() ? null : desc.trim());

            Integer pointId = JsonValues.toInt(r.get("pointId"), at + " pointId");
            if (pointId == null) {
                desired.add(row);
                continue;
            }
            if (!existing.containsKey(pointId)) {
                throw new IllegalArgumentException(at + "：pointId=" + pointId + " 不属于这张图（stage=" + id + "）的刷新点");
            }
            if (!touched.add(pointId)) {
                throw new IllegalArgumentException(at + "：pointId=" + pointId + " 重复出现");
            }
            row.setId(pointId);
            desired.add(row);
        }

        int removed = 0;
        for (Integer existingId : existing.keySet()) {
            if (!touched.contains(existingId)) {
                mapSpawnPointMapper.deleteById(existingId);
                removed++;
            }
        }
        int added = 0;
        int updated = 0;
        for (MapSpawnPoint row : desired) {
            if (row.getId() == null) {
                if (!spawnPointIdHasDefault()) {
                    row.setId(nextSpawnPointId());
                }
                mapSpawnPointMapper.insert(row);
                added++;
            } else {
                mapSpawnPointMapper.updateById(row);
                updated++;
            }
        }
        log.info("[MapAdmin] 保存刷新点 地图id={} 新增 {} 改 {} 删 {}", id, added, updated, removed);

        Map<String, Object> out = points(id);
        out.put("added", added);
        out.put("updated", updated);
        out.put("removed", removed);
        return out;
    }

    /** `mapspawnpoint.id` 在库里有没有默认值/序列（探一次，缓存）。 */
    private boolean spawnPointIdHasDefault() {
        Boolean cached = spawnPointIdHasDefault;
        if (cached != null) {
            return cached;
        }
        boolean has = false;
        String sql = "select count(*) from information_schema.columns "
                + "where table_schema = 'gamedb' and table_name = 'mapspawnpoint' "
                + "and column_name = 'id' and column_default is not null";
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                has = rs.getInt(1) > 0;
            }
        } catch (java.sql.SQLException e) {
            log.warn("[MapAdmin] 探测 mapspawnpoint.id 是否有序列失败：{}（按「没有」处理，用 max+1 分配）", e.toString());
            has = false;
        }
        spawnPointIdHasDefault = has;
        if (!has) {
            log.warn("[MapAdmin] mapspawnpoint.id 没有序列/默认值 ⇒ 新增刷新点由应用分配 max(id)+1。"
                    + "建议补一个序列（DDL 见 docs/plans/2026-09-22-pt-web-admin-npc-map-design.md §十）");
        }
        return has;
    }

    /** `max(id)+1`（仅在库里没有序列时用；单管理员场景足够）。 */
    private int nextSpawnPointId() {
        List<Map<String, Object>> rows = mapSpawnPointMapper.selectMaps(
                new QueryWrapper<MapSpawnPoint>().select("coalesce(max(id), 0) + 1 as nextid"));
        Object v = rows.isEmpty() ? null : rows.get(0).get("nextid");
        return v instanceof Number n ? n.intValue() : 1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> body, String key) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        Object raw = body.get(key);
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("请求体缺少 " + key + " 数组");
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException(key + " 里出现非对象元素：" + o);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            m.forEach((k, v) -> row.put(String.valueOf(k), v));
            out.add(row);
        }
        return out;
    }

    private static int requiredInt(Map<String, Object> row, String key, String at) {
        Integer v = JsonValues.toInt(row.get(key), at + " " + key);
        if (v == null) {
            throw new IllegalArgumentException(at + "：缺少 " + key);
        }
        return v;
    }

    /** `enabled`/`onlyGm`：0/1（缺省 1 / 0，与语料一致）。 */
    private static int flag(Map<String, Object> row, String key, String at) {
        Integer v = JsonValues.toInt(row.get(key), at + " " + key);
        if (v == null) {
            return key.equals("enabled") ? 1 : 0;
        }
        if (v != 0 && v != 1) {
            throw new IllegalArgumentException(at + "：" + key + " 只能是 0 或 1（收到 " + v + "）");
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 汇聚点三段
    // ------------------------------------------------------------------

    /**
     * 这张图的刷怪配置（`mapmonster` 一行）：普通怪槽位 + Boss/副怪槽位 + 上限/间隔。
     *
     * @return null 表示地图不存在；否则 `{configured, maxMonsters, interval, waves[], bossWaves[]}`
     *         （`waves` 里每个槽位给 `monsterName`/`count`/`monsterId`（**主键**，供跳转；认不出名字时为 null））
     */
    public Map<String, Object> spawn(Integer id) {
        MapList map = id == null ? null : mapListMapper.selectById(id);
        if (map == null) {
            return null;
        }
        // 槽位存的就是 **monsterlist.id**（2026-09-25 迁移）；显示名由 id 解析
        Map<Integer, String> monsterNameById = monsterNamesById();
        MapMonster row = null;
        for (MapMonster mm : mapMonsterMapper.selectList(null)) {
            if (id.toString().equals(mm.getStage())) {
                row = mm;
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", row != null);
        out.put("maxMonsters", row == null ? null : row.getMaxMonsters());
        out.put("interval", row == null ? null : row.getInterval());
        List<Map<String, Object>> waves = new ArrayList<>();
        List<Map<String, Object>> bossWaves = new ArrayList<>();
        if (row != null) {
            for (int i = 1; i <= WAVE_SLOTS; i++) {
                addWave(waves, i, waveSlot(row, i), waveCount(row, i), monsterNameById);
            }
            for (int i = 1; i <= BOSS_SLOTS; i++) {
                addBossWave(bossWaves, "boss", i, bossSlot(row, i), monsterNameById);
                addBossWave(bossWaves, "sub", i, subSlot(row, i), monsterNameById);
            }
        }
        out.put("waves", waves);
        out.put("bossWaves", bossWaves);
        return out;
    }

    private void addWave(List<Map<String, Object>> out, int slot, Integer monsterId, Integer count,
                         Map<Integer, String> monsterNameById) {
        if (monsterId == null) {
            return;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("slot", slot);
        w.put("monsterId", monsterId);
        w.put("monsterName", monsterNameById.get(monsterId));   // 未知的 id ⇒ null（JS 端显示 #id）
        w.put("count", count);
        out.add(w);
    }

    private void addBossWave(List<Map<String, Object>> out, String kind, int slot, Integer monsterId,
                             Map<Integer, String> monsterNameById) {
        if (monsterId == null) {
            return;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("kind", kind);
        w.put("slot", slot);
        w.put("monsterId", monsterId);
        w.put("monsterName", monsterNameById.get(monsterId));
        out.add(w);
    }

    /** id → 显示名（monsterlist.name，英文名）：管理端列表展示用。 */
    private Map<Integer, String> monsterNamesById() {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (MonsterList m : monsterListMapper.selectList(null)) {
            if (m.getId() != null) {
                out.put(m.getId(), m.getName());
            }
        }
        return out;
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

    private static Integer waveCount(MapMonster mm, int i) {
        return switch (i) {
            case 1 -> mm.getCount1();
            case 2 -> mm.getCount2();
            case 3 -> mm.getCount3();
            case 4 -> mm.getCount4();
            case 5 -> mm.getCount5();
            case 6 -> mm.getCount6();
            case 7 -> mm.getCount7();
            case 8 -> mm.getCount8();
            case 9 -> mm.getCount9();
            case 10 -> mm.getCount10();
            case 11 -> mm.getCount11();
            case 12 -> mm.getCount12();
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

    /**
     * 这张图上的 NPC（`mapnpc`）。
     *
     * @return null 表示地图不存在；否则 `{count, npcs:[{placeId, npcId, npcName, x, y, z, angle, enabled, onlyGm}]}`
     */
    public Map<String, Object> npcs(Integer id) {
        MapList map = id == null ? null : mapListMapper.selectById(id);
        if (map == null) {
            return null;
        }
        Map<Integer, String> npcNameById = new LinkedHashMap<>();
        for (NpcList n : npcListMapper.selectList(null)) {
            npcNameById.put(n.getId(), n.getName());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MapNpc p : mapNpcMapper.selectList(
                new QueryWrapper<MapNpc>()
                        .eq("stage", id).orderByAsc("id"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("placeId", p.getId());
            row.put("npcId", p.getIdNpc());
            row.put("npcName", p.getIdNpc() == null ? null : npcNameById.get(p.getIdNpc()));
            row.put("x", p.getX());
            row.put("y", p.getY());
            row.put("z", p.getZ());
            row.put("angle", p.getAngle());
            row.put("enabled", p.getEnabled());
            row.put("onlyGm", p.getOnlyGm());
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("npcs", out);
        return result;
    }

    /**
     * 这张图的刷新点（`mapspawnpoint`）—— 实测每图 1~149 个。
     *
     * @return null 表示地图不存在；否则 `{count, points:[{pointId, x, z, description}]}`
     */
    public Map<String, Object> points(Integer id) {
        MapList map = id == null ? null : mapListMapper.selectById(id);
        if (map == null) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MapSpawnPoint p : mapSpawnPointMapper.selectList(
                new QueryWrapper<MapSpawnPoint>()
                        .eq("stage", id).orderByAsc("id"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pointId", p.getId());
            row.put("x", p.getX());
            row.put("z", p.getZ());
            row.put("description", p.getDescription());
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("points", out);
        return result;
    }
}
