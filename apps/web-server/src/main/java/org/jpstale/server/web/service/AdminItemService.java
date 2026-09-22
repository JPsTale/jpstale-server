package org.jpstale.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.server.web.admin.AdminEntityService;
import org.jpstale.server.web.admin.ColumnRegistry;
import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.item.ItemCategory;
import org.jpstale.server.web.item.ItemColumnRegistry;
import org.jpstale.server.web.item.ItemColumnSemantics;
import org.jpstale.server.web.item.ItemQueryParams;
import org.jpstale.server.web.simulator.SimulatorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品管理（`gamedb.itemlist`）：列表 / 详情 / 修改 / Mix 配方。
 *
 * <p>
 * 列表 / 详情 / 修改的**通用部分**（反射列序、计数分页、部分更新的白名单与类型校验、LIKE 转义、
 * 区间相交）在 {@link AdminEntityService}；本类只给物品特有的三样：
 * 列注册表（段归属）、四组固定筛选条件、Mix 配方与筛选候选项。
 *
 * <p>
 * ⚠ 本表的两条特有口径：
 * <ul>
 *   <li>筛选是四组固定条件（身份 / 数值门槛 / 装备语义 / 攻防数值），不接受任意列组合。</li>
 *   <li>攻防数值按**列对做区间相交**（`Xmax &gt;= min AND Xmin &lt;= max`），
 *       不是"两端都落在范围内" —— 后者会整批漏掉区间宽的基础装，而它看起来很像对的。</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminItemService extends AdminEntityService<ItemList, ItemQueryParams> {

    private final ItemListMapper itemListMapper;
    private final SimulatorService simulatorService;

    public AdminItemService(ItemListMapper itemListMapper, SimulatorService simulatorService) {
        this.itemListMapper = itemListMapper;
        this.simulatorService = simulatorService;
    }

    @Override
    protected ColumnRegistry registry() {
        return ItemColumnRegistry.REGISTRY;
    }

    @Override
    protected BaseMapper<ItemList> mapper() {
        return itemListMapper;
    }

    @Override
    protected ItemList newPatch() {
        return new ItemList();
    }

    @Override
    protected ColumnSemantics.Semantics semanticsOf(String column) {
        return ItemColumnSemantics.of(column);
    }

    @Override
    protected String logTag() {
        return "[ItemAdmin]";
    }

    @Override
    protected String entityLabel() {
        return "物品定义";
    }

    @Override
    protected void applyFilters(QueryWrapper<ItemList> w, ItemQueryParams q) {
        // 身份
        if (q.getNameLike() != null) {
            // 大小写**不敏感**（ILIKE）：PG 的 LIKE 区分大小写，而库里名字是 "Sword" 这种写法，
            // 用 LIKE 会让搜 "sword" 一条都不中 —— 搜索框不该有这种行为。
            // 同时把 % / _ 转义成字面量：搜索框里输入 % 应该是"找含 % 的名字"，不是通配。
            w.apply("name ILIKE {0}", "%" + escapeLike(q.getNameLike()) + "%");
        }
        if (q.getIdcode() != null) {
            w.eq("idcode", q.getIdcode());
        }
        if (q.getCategory() != null) {
            w.eq("category", q.getCategory());
        }
        // 数值门槛（单列区间）
        range(w, "reqlevel", q.getReqlevelMin(), q.getReqlevelMax());
        range(w, "price", q.getPriceMin(), q.getPriceMax());
        range(w, "weight", q.getWeightMin(), q.getWeightMax());
        // 装备语义（数据库原值，不翻译语义）
        if (q.getWeaponclass() != null) {
            w.eq("weaponclass", q.getWeaponclass());
        }
        if (q.getClassitem() != null) {
            w.eq("classitem", q.getClassitem());
        }
        if (q.getModelposition() != null) {
            w.eq("modelposition", q.getModelposition());
        }
        // 攻防数值：列对上的区间相交
        overlap(w, "defensemin", "defensemax", q.getDefenseMin(), q.getDefenseMax());
        overlap(w, "atkpow1min", "atkpow1max", q.getAtkpow1Min(), q.getAtkpow1Max());
        overlap(w, "atkpow2min", "atkpow2max", q.getAtkpow2Min(), q.getAtkpow2Max());
        overlap(w, "atkratingmin", "atkratingmax", q.getAtkratingMin(), q.getAtkratingMax());
        overlap(w, "absorbmin", "absorbmax", q.getAbsorbMin(), q.getAbsorbMax());
        overlap(w, "blockmin", "blockmax", q.getBlockMin(), q.getBlockMax());
    }

    /**
     * 筛选用的取值清单（目前只有 `category`）：**原值 + 计数**，供前端做带计数的候选项。
     *
     * <p>
     * 为什么要计数、且不归并：库里 `category` 有 46 个取值，其中存在**同名不同大小写**的
     * （`event 24` / `Event 4`、`costumes 3` / `Costume 66`、`SOD 1` / `SoD 7`、`quests 27` / `Quest 14`）。
     * 而 `category` 筛选是**精确匹配**（保持"忠实于数据库"），所以下拉里若有计数，
     * 使用者一眼就能看出"这是两拨"；若归并成一个选项，就与数据库不一致了。
     */
    public Map<String, Object> facets() {
        List<Map<String, Object>> rows = itemListMapper.selectMaps(
                new QueryWrapper<ItemList>()
                        .select("category", "count(*) as cnt")
                        .groupBy("category")
                        .orderByDesc("cnt"));
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", r.get("category"));
            item.put("count", r.get("cnt"));
            categories.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", categories);
        return out;
    }

    /**
     * 物品搜索 —— 供**物品选择器**用（掉落编辑、将来的 NPC 商店编辑共用同一个）。
     *
     * <p>
     * 为什么单独一个接口：列表接口每行给全部 108 列，选择器只要 5 个字段即可；
     * 而且选择器的语义是"按码或名字找一件物品"，与"按四组条件筛一批物品"是两回事。
     *
     * <p>
     * 匹配：`codeimg1` **或** `name` 的大小写不敏感子串（通配符按字面量转义，同列表接口）；
     * `q` 为空则按 idcode 升序给前 N 条（用于"还没输就展示点什么"）。
     */
    public List<Map<String, Object>> search(String q, int limit) {
        int n = Math.max(1, Math.min(limit, 50));
        QueryWrapper<ItemList> w = new QueryWrapper<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + escapeLike(q.trim()) + "%";
            w.apply("(codeimg1 ILIKE {0} OR name ILIKE {0})", like);
        }
        w.orderByAsc("idcode");
        w.last("LIMIT " + n);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ItemList it : itemListMapper.selectList(w)) {
            Map<String, Object> row = new LinkedHashMap<>();
            // id = **itemlist 主键**：管理端的跨页引用一律传主键（一个 codeimg1 可能对应多行）
            row.put("id", it.getId());
            row.put("idcode", it.getIdCode());
            row.put("codeimg1", it.getCodeImg1());
            row.put("name", it.getName());
            row.put("category", it.getCategory());
            row.put("price", it.getPrice());
            out.add(row);
        }
        return out;
    }

    /**
     * 该物品可用的 Mix 配方（详情浮层的 Mix 下拉用）。
     *
     * <p>
     * ⚠ 这里**委托** {@link SimulatorService#mixes}：Mix 的
     * "分类 → `typemixname` → 配方行 → 效果字段" 那套映射目前只有那一份实现，
     * 复制一份就是第二份（必然漂移）。按计划 SimulatorService 会整块删除，
     * 届时把那几个方法搬进本类 —— 现在先不重复实现。
     *
     * <p>
     * "idcode → wartale 分类"的派生只发生在**服务端这一处**，下发的行数据保持全部列原样。
     *
     * @return null 表示物品不存在；空列表表示该物品没有 Mix 配方
     */
    public List<Map<String, Object>> mixes(int id) {
        ItemList e = itemListMapper.selectById(id);
        if (e == null) {
            return null;
        }
        ItemCategory.Category cat = ItemCategory.of(e.getIdCode());
        if (cat == null) {
            // 该 idcode 前缀不在 wartale 分类表里 ⇒ 没有对应的 Mix 族
            return List.of();
        }
        return simulatorService.mixes(cat.getType(), cat.getName());
    }
}
