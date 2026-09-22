package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.MixList;
import org.jpstale.dao.gamedb.mapper.MixListMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成配方表（`gamedb.mixlist`，283 条）的加载与匹配 —— `MixType` + 材料计数 → 配方。
 *
 * <p>
 * **为什么读 `mixlist` 而不是那 4 张 `mix*` 表**：实测 `mixitem`/`mixvalue`/`mixeffect`/`mixeffecttype`
 * **全是空表（0 行）**，只有 `mixlist` 有真实数据（283 条）；EU 的服务端也只读 `MixList`
 * （`Server/server/MixHandler.cpp:44` 的 `SELECT TOP 70 * FROM MixList`）。那 4 张是另一套（规范化）模型，
 * 我们不用也不动（见 `docs/锻造与合成-我们的实现方案.md` §2）。
 *
 * <p>
 * 加载在构造时做一次（283 行，一次性读全表）；`reload()` 供管理端改配方后刷新。
 * **不静默**：未知 `typemix`、空类型、无效果的配方都会计数并 `log.warn` 报出来（纠错 #12）。
 */
@Service
public class MixRecipeService {

    private static final Logger log = LoggerFactory.getLogger(MixRecipeService.class);

    private final MixListMapper mapper;
    private volatile List<MixRecipe> recipes = List.of();
    /** 配方 id（`mixuniqueid`）→ 配方：物品只存 id，读取侧（如"药水槽容量"）靠它反查。 */
    private volatile Map<Integer, MixRecipe> byUniqueId = Map.of();

    /**
     * ⚠ **必须标 `@Autowired`**（2026-09-22 血的教训）：本类有两个构造函数
     * （这个收 mapper 的 + 下面给 `forRows` 用的私有 no-arg）。
     * Spring 在"多个构造函数且都没标 `@Autowired`"时会**回退到无参构造**（**私有也能反射调用**），
     * 于是 mapper 恒为 null、`reload()` 从没执行 ⇒ **这张%s表恒为空**：
     * 合成属性全不生效（`byUniqueId` 永远 null）、战斗养进度永远不涨（`rowForLevel` 永远 null 且静默 return）。
     * 症状是"代码/库/单测全对，跑起来就是不动"，日志里连一条载入日志都没有 —— 极难猜，故留此注释。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MixRecipeService(MixListMapper mapper) {
        this.mapper = mapper;
        reload();
    }

    /**
     * **离线构造**（单测/未来管理端预览用）：直接给配方行，不接 DB。
     * 日志口径与 {@link #reload()} 一致（未知类型/无效果/重复 id 都会报出来，不静默）。
     */
    public static MixRecipeService forRows(List<MixList> rows) {
        MixRecipeService svc = new MixRecipeService();
        svc.index(rows);
        return svc;
    }

    private MixRecipeService() {
        this.mapper = null;
    }

    /** 重新载入配方表；返回条数。 */
    public synchronized int reload() {
        return index(mapper.selectList(null));
    }

    /** 行 → 表（纯逻辑，日志与校验都在这；`reload()`/`forRows()` 共用）。 */
    private synchronized int index(List<MixList> rows) {
        List<MixRecipe> list = new ArrayList<>(rows == null ? 0 : rows.size());
        Map<Integer, MixRecipe> map = new LinkedHashMap<>();
        int unknownType = 0;
        int noEffect = 0;
        int noUniqueId = 0;
        int dupUniqueId = 0;
        for (MixList row : rows == null ? List.<MixList>of() : rows) {
            MixRecipe r = MixRecipe.of(row);
            if (r.type() == MixType.UNKNOWN) {
                unknownType++;
                log.warn("[Mix] 配方 id={} 的 typemix={} 不在已知类型里（该条不会被任何物品匹配到）",
                        r.dbId(), row.getTypeMix());
                continue;
            }
            if (r.mask() == 0) {
                noEffect++;
                log.warn("[Mix] 配方 id={}（{}，{}）没有任何效果位", r.dbId(), r.type().label(), r.description());
            }
            if (r.uniqueId() <= 0) {
                noUniqueId++;
            } else if (map.putIfAbsent(r.uniqueId(), r) != null) {
                dupUniqueId++;
                log.warn("[Mix] 配方 id（mixuniqueid）{} 重复 —— 反查只会命中先出现的那条", r.uniqueId());
            }
            list.add(r);
        }
        recipes = List.copyOf(list);
        byUniqueId = Map.copyOf(map);
        log.info("[Mix] 配方载入 {} 条（跳过未知类型 {}，无效果 {}，无 mixuniqueid {}，重复 id {}）；"
                        + "反向表 {} 条，uniqueId 范围 {}-{}",
                recipes.size(), unknownType, noEffect, noUniqueId, dupUniqueId,
                map.size(),
                map.isEmpty() ? 0 : java.util.Collections.min(map.keySet()),
                map.isEmpty() ? 0 : java.util.Collections.max(map.keySet()));
        if (map.isEmpty()) {
            // 空表是"合成完全不生效"的根因，必须喊出来（读库连错/表名不对时就是这个）
            log.error("[Mix] 反向表是**空的** —— 合成属性永远不会生效。先查 mapper/库连接与 gamedb.mixlist 是否有行。");
        } else if (!map.containsKey(319)) {
            log.warn("[Mix] 反向表里**没有** mixuniqueid=319（本次排查用的盾牌配方）。"
                    + "若库里确实有这一行，说明 selectList 取到的行没进来 —— 看上面'跳过未知类型'的警告。");
        }
        return recipes.size();
    }

    public List<MixRecipe> recipes() {
        return recipes;
    }

    /** 按配方 id 反查（物品的 `aging_num2` 存的就是它）。 */
    /** 诊断用：表里前 n 个 uniqueId（"查不到配方"时打出样例，一眼看出是空表还是 id 不对） */
    public java.util.List<Integer> sampleUniqueIds(int n) {
        return byUniqueId.keySet().stream().sorted().limit(n).toList();
    }

    public MixRecipe byUniqueId(int uniqueId) {
        return byUniqueId.get(uniqueId);
    }

    /**
     * 匹配：**基材类一致 + 全部 14 档石头个数逐个严格相等**（照抄 EU `MixHandler.cpp:479-500`）。
     *
     * @param type   目标物品的基材类（{@link MixType#ofItemCode}）
     * @param counts 玩家投入的石头按档位计数，下标 0 = 档位 1（Lucidy）
     * @return 命中的配方；没有则 null（调用方按"配方不存在"回错，别兜底成别的配方）
     */
    public MixRecipe find(MixType type, int[] counts) {
        if (type == MixType.UNKNOWN) {
            return null;
        }
        for (MixRecipe r : recipes) {
            if (r.type() == type && r.matchesStones(counts)) {
                return r;
            }
        }
        return null;
    }
}
