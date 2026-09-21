package org.jpstale.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.server.web.dto.AdminItemColumn;
import org.jpstale.server.web.item.ItemCategory;
import org.jpstale.server.web.item.ItemColumnRegistry;
import org.jpstale.server.web.item.ItemColumnSemantics;
import org.jpstale.server.web.item.ItemQueryParams;
import org.jpstale.server.web.simulator.SimulatorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品管理（`gamedb.itemlist`）：列表 / 详情 / 修改 / Mix 配方。
 *
 * <p>
 * 三条口径（设计文档 §二、§5）：
 * <ul>
 *   <li>**对外一律用数据库列名**：行是 `Map&lt;列名, 值&gt;`，字段全集由
 *       {@link ItemColumnRegistry} 反射生成 —— "全部列"是结构保证，不是"我记得的那些列"。</li>
 *   <li>筛选是四组固定条件（身份 / 数值门槛 / 装备语义 / 攻防数值），不接受任意列组合。</li>
 *   <li>攻防数值按**列对做区间相交**（`Xmax &gt;= min AND Xmin &lt;= max`），
 *       不是"两端都落在范围内" —— 后者会整批漏掉区间宽的基础装，而它看起来很像对的。</li>
 * </ul>
 *
 * <p>
 * ⚠ 分页**没有**用 `Page`：本仓没有 `PaginationInnerInterceptor`（且它所在的
 * `mybatis-plus-jsqlparser` 不在依赖里），没有拦截器时 `selectPage` 会**不报错地**返回全表
 * 且 `total` 恒为 0。这里用 `selectCount` + 夹紧后的 `LIMIT/OFFSET`（设计文档 §5.2.1）。
 */
@Slf4j
@Service
public class AdminItemService {

    private final ItemListMapper itemListMapper;
    private final SimulatorService simulatorService;

    public AdminItemService(ItemListMapper itemListMapper, SimulatorService simulatorService) {
        this.itemListMapper = itemListMapper;
        this.simulatorService = simulatorService;
    }

    // ------------------------------------------------------------------
    // 列清单
    // ------------------------------------------------------------------

    /**
     * 全部列的元信息，**按段顺序**输出（身份 → 需求 → 基础属性 → 职业特效 → 其他），
     * 段内保持实体声明顺序。
     *
     * <p>
     * 为什么在这里排序而不是让前端排：段序的唯一来源是
     * {@link ItemColumnRegistry#SECTION_ORDER}。前端若再写一份顺序表，两处就会漂
     * （实测踩过：按实体声明顺序输出时，`quest*` 在实体里靠前，于是"其他"段跑到了第二位）。
     */
    public List<AdminItemColumn> columns() {
        List<AdminItemColumn> out = new ArrayList<>(ItemColumnRegistry.size());
        for (ItemColumnRegistry.Column col : ItemColumnRegistry.orderedColumns()) {
            AdminItemColumn dto = new AdminItemColumn();
            dto.setColumn(col.name());
            dto.setJavaType(col.type().getSimpleName());
            dto.setSection(col.section());
            dto.setSectionLabelKey(ItemColumnRegistry.SECTION_LABEL_KEYS
                    .getOrDefault(col.section(), "admin.section." + col.section()));
            dto.setPrimaryKey(col.primaryKey());
            dto.setEditable(col.editable());
            dto.setFilterable(col.filterable());
            // 取值语义：显示翻名字、编辑给下拉（用户反馈"纯数字没有语义"）。
            // ⚠ 只发 key，不发文案 —— 文案由页面按 locale 翻译（用户要求能搞 i18n）。
            ItemColumnSemantics.Semantics sem = ItemColumnSemantics.of(col.name());
            dto.setKind(sem.kind().name());
            List<AdminItemColumn.Option> options = new ArrayList<>(sem.options().size());
            for (ItemColumnSemantics.Option o : sem.options()) {
                AdminItemColumn.Option opt = new AdminItemColumn.Option();
                opt.setValue(o.value());
                opt.setLabelKey(o.labelKey());
                options.add(opt);
            }
            dto.setOptions(options);
            List<AdminItemColumn.Bit> bits = new ArrayList<>(sem.bits().size());
            for (ItemColumnSemantics.Bit b : sem.bits()) {
                AdminItemColumn.Bit bit = new AdminItemColumn.Bit();
                bit.setValue(b.value());
                bit.setLabelKey(b.labelKey());
                bits.add(bit);
            }
            dto.setBits(bits);
            dto.setRowLabelKey(sem.rowLabelKey());
            dto.setRowPart(sem.rowPart());
            dto.setUnit(sem.unit());
            out.add(dto);
        }
        return out;
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

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 列表：四组筛选 + 排序 + 分页；每行**全部列**。
     *
     * @return `{ total, page, size, totalPages, items }`（键与既有的 simulator 列表一致）
     */
    public Map<String, Object> list(ItemQueryParams q) {
        QueryWrapper<ItemList> w = new QueryWrapper<>();
        applyFilters(w, q);

        long total = itemListMapper.selectCount(w);

        String sortColumn = q.getSort() != null ? q.getSort() : "id";
        w.orderBy(true, !q.isDesc(), sortColumn);
        // 夹紧已在 ItemQueryParams 里做过；这里再算一次 offset。列名来自注册表白名单，值是整数，
        // 故拼接无注入面（.last 是裸片段，这也是为什么"夹紧"不是可选优化）。
        long offset = (long) (q.getPage() - 1) * q.getSize();
        w.last("LIMIT " + q.getSize() + " OFFSET " + offset);

        List<Map<String, Object>> items = new ArrayList<>();
        for (ItemList e : itemListMapper.selectList(w)) {
            items.add(toRow(e));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", q.getPage());
        result.put("size", q.getSize());
        result.put("totalPages", q.getSize() <= 0 ? 0 : (total + q.getSize() - 1) / q.getSize());
        result.put("items", items);
        return result;
    }

    private void applyFilters(QueryWrapper<ItemList> w, ItemQueryParams q) {
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

    /** 单列区间：只给一端也生效（"至少 N" / "至多 N"）。 */
    private void range(QueryWrapper<ItemList> w, String column, Integer min, Integer max) {        if (min != null) {
            w.ge(column, min);
        }
        if (max != null) {
            w.le(column, max);
        }
    }

    /**
     * **区间相交**：命中条件 `maxColumn &gt;= 下界 AND minColumn &lt;= 上界`。
     *
     * <p>
     * 只给一端时取自然半条件（下界 → "该件的上界够得着下界"；上界 → "该件的下界不超上界"）。
     * 反例（写成"包含"就会漏）：`defensemin=1, defensemax=200` 的基础装，按"防御 100~150" 筛，
     * 必须命中 —— 它的区间与 [100,150] 有重叠。
     */
    private void overlap(QueryWrapper<ItemList> w, String minColumn, String maxColumn,
                         Integer lowerBound, Integer upperBound) {
        if (lowerBound != null) {
            w.ge(maxColumn, lowerBound);
        }
        if (upperBound != null) {
            w.le(minColumn, upperBound);
        }
    }

    /** 把 LIKE 的通配符转成字面量（PG 的默认转义符是反斜杠）。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    /** 按主键取一行全部列；不存在返回 null。 */
    public Map<String, Object> getById(int id) {
        ItemList e = itemListMapper.selectById(id);
        return e == null ? null : toRow(e);
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    /**
     * 部分更新：只改请求体里出现的列。
     *
     * @param changes 列名 → 新值；列名必须是库里的列、且在可改列内（主键与时间戳类列排除在外）
     * @return 更新后的整行；`id` 不存在返回 null
     * @throws IllegalArgumentException 未知列名 / 不可改的列 / 值类型不相容
     */
    @Transactional
    public Map<String, Object> update(int id, Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("请求体为空：至少要给一列");
        }
        if (itemListMapper.selectById(id) == null) {
            return null;
        }
        ItemList patch = new ItemList();
        patch.setId(id);
        List<String> touched = new ArrayList<>();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String column = e.getKey();
            ItemColumnRegistry.Column col = ItemColumnRegistry.byName(column);
            if (col == null) {
                throw new IllegalArgumentException("未知的列名：" + column);
            }
            if (col.primaryKey()) {
                throw new IllegalArgumentException("主键 " + column + " 不允许修改");
            }
            if (!col.editable()) {
                throw new IllegalArgumentException("列 " + column + " 不允许修改");
            }
            ItemColumnRegistry.set(col, patch, coerce(col, e.getValue()));
            touched.add(column);
        }
        // MyBatis-Plus 默认忽略 null ⇒ 只写了请求体里的列，其余保持原值（部分更新）
        itemListMapper.updateById(patch);
        // 一行 INFO 记录"谁改了哪几列"（不含旧值 —— 复用/回滚不是本期目标，见设计文档 §5.4）
        log.info("[ItemAdmin] 修改物品定义 id={} 列={}", id, touched);
        return toRow(itemListMapper.selectById(id));
    }

    /** 把 JSON 里的值转成实体字段的类型；转换不过一律拒绝，不做"猜"式兜底。 */
    private Object coerce(ItemColumnRegistry.Column col, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("列 " + col.name() + " 不支持传 null 清空（当前语义：null = 不改）");
        }
        Class<?> type = col.type();
        if (type == String.class) {
            if (!(value instanceof String s)) {
                throw new IllegalArgumentException("列 " + col.name() + " 需要字符串，收到 " + value.getClass().getSimpleName());
            }
            return s;
        }
        if (type == Integer.class) {
            if (value instanceof Integer i) {
                return i;
            }
            if (value instanceof Long l) {
                if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("列 " + col.name() + " 超出整数范围：" + l);
                }
                return l.intValue();
            }
            if (value instanceof String s) {
                try {
                    return Integer.valueOf(s.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("列 " + col.name() + " 不是整数：" + s);
                }
            }
            throw new IllegalArgumentException("列 " + col.name() + " 需要整数，收到 " + value.getClass().getSimpleName());
        }
        if (type == Double.class) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            if (value instanceof String s) {
                try {
                    return Double.valueOf(s.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("列 " + col.name() + " 不是小数：" + s);
                }
            }
            throw new IllegalArgumentException("列 " + col.name() + " 需要小数，收到 " + value.getClass().getSimpleName());
        }
        throw new IllegalStateException("未支持的字段类型：" + type.getName() + "（列 " + col.name() + "）");
    }

    // ------------------------------------------------------------------
    // Mix 配方
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 行转换
    // ------------------------------------------------------------------

    /** 实体 → `Map<列名, 值>`，键**按段顺序**（与 /columns 一致），**包含全部列**（含 id）。 */
    private Map<String, Object> toRow(ItemList e) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ItemColumnRegistry.Column col : ItemColumnRegistry.orderedColumns()) {
            row.put(col.name(), ItemColumnRegistry.get(col, e));
        }
        return row;
    }
}
