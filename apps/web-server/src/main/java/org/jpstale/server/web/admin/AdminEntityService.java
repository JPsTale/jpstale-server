package org.jpstale.server.web.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.web.dto.AdminColumn;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端"一张表"的通用读写：列清单 / 列表 / 详情 / 部分更新（物品与怪物共用）。
 *
 * <p>
 * 三条口径（物品设计文档 §二、§5）：
 * <ul>
 *   <li>**对外一律用数据库列名**：行是 `Map&lt;列名, 值&gt;`，字段全集由 {@link ColumnRegistry}
 *       反射生成 —— "全部列"是结构保证，不是"我记得的那些列"。</li>
 *   <li>行内键序 = `/columns` 的列序（都走注册表的段序）。</li>
 *   <li>更新是**部分更新**：只改请求体里出现的列，逐列校验"存在 / 可改 / 类型相容"。</li>
 * </ul>
 *
 * <p>
 * ⚠ 分页**没有**用 `Page`：本仓没有 `PaginationInnerInterceptor`（且它所在的
 * `mybatis-plus-jsqlparser` 不在依赖里），没有拦截器时 `selectPage` 会**不报错地**返回全表
 * 且 `total` 恒为 0。这里用 `selectCount` + 夹紧后的 `LIMIT/OFFSET`（设计文档 §5.2.1）。
 *
 * <p>
 * ⚠ **为什么是抽象基类而不是各写一份**：筛选组与取值语义确实因表而异，但"反射列序 + 计数分页 +
 * 部分更新白名单 + 类型转换 + LIKE 转义 + 区间相交"这些是**同一套判定**。抄第二份的下场是
 * 两边慢慢漂（AGENTS #15：同一个判定在仓库里出现第二份，哪怕只差一点，就是 bug 的种子）。
 */
@Slf4j
public abstract class AdminEntityService<T, Q extends AdminQueryParams> {

    protected abstract ColumnRegistry registry();

    protected abstract BaseMapper<T> mapper();

    /** 该表的固定筛选组。 */
    protected abstract void applyFilters(QueryWrapper<T> w, Q q);

    /** 该列的取值语义（显示翻名字、编辑给下拉）。 */
    protected abstract ColumnSemantics.Semantics semanticsOf(String column);

    /** 空实体，用于部分更新（只有被改的列会被赋值）。 */
    protected abstract T newPatch();

    /** 日志前缀，如 `[ItemAdmin]`。 */
    protected abstract String logTag();

    /** 日志里的实体名，如 `物品定义` / `怪物定义`。 */
    protected abstract String entityLabel();

    // ------------------------------------------------------------------
    // 列清单
    // ------------------------------------------------------------------

    /**
     * 全部列的元信息，**按段顺序**输出，段内保持实体声明顺序。
     *
     * <p>
     * 为什么在这里排序而不是让前端排：段序的唯一来源是 {@link ColumnRegistry} 的构造参数。
     * 前端若再写一份顺序表，两处就会漂（实测踩过：按实体声明顺序输出时，`quest*` 在实体里靠前，
     * 于是"其他"段跑到了第二位）。
     */
    public List<AdminColumn> columns() {
        ColumnRegistry reg = registry();
        List<AdminColumn> out = new ArrayList<>(reg.size());
        for (ColumnRegistry.Column col : reg.orderedColumns()) {
            AdminColumn dto = new AdminColumn();
            dto.setColumn(col.name());
            dto.setJavaType(col.type().getSimpleName());
            dto.setSection(col.section());
            dto.setSectionLabelKey(reg.sectionLabelKey(col.section()));
            dto.setPrimaryKey(col.primaryKey());
            dto.setEditable(col.editable());
            dto.setFilterable(col.filterable());
            // 取值语义：显示翻名字、编辑给下拉（用户反馈"纯数字没有语义"）。
            // ⚠ 只发 key，不发文案 —— 文案由页面按 locale 翻译（用户要求能搞 i18n）。
            ColumnSemantics.Semantics sem = semanticsOf(col.name());
            dto.setKind(sem.kind().name());
            List<AdminColumn.Option> options = new ArrayList<>(sem.options().size());
            for (ColumnSemantics.Option o : sem.options()) {
                AdminColumn.Option opt = new AdminColumn.Option();
                opt.setValue(o.value());
                opt.setLabelKey(o.labelKey());
                options.add(opt);
            }
            dto.setOptions(options);
            List<AdminColumn.TextOption> textOptions = new ArrayList<>(sem.textOptions().size());
            for (ColumnSemantics.TextOption o : sem.textOptions()) {
                AdminColumn.TextOption opt = new AdminColumn.TextOption();
                opt.setValue(o.value());
                opt.setLabelKey(o.labelKey());
                textOptions.add(opt);
            }
            dto.setTextOptions(textOptions);
            List<AdminColumn.Bit> bits = new ArrayList<>(sem.bits().size());
            for (ColumnSemantics.Bit b : sem.bits()) {
                AdminColumn.Bit bit = new AdminColumn.Bit();
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

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 列表：该表的固定筛选组 + 排序 + 分页；每行**全部列**。
     *
     * @return `{ total, page, size, totalPages, items }`
     */
    public Map<String, Object> list(Q q) {
        QueryWrapper<T> w = new QueryWrapper<>();
        applyFilters(w, q);

        // ⚠ 计数必须在加 ORDER BY / LIMIT **之前**取（此时 wrapper 上只有筛选条件）
        long total = mapper().selectCount(w);

        w.orderBy(true, !q.isDesc(), q.sortColumnOrDefault(registry()));
        // 夹紧已在 AdminQueryParams 里做过；列名来自注册表白名单、值是整数，
        // 故拼接无注入面（.last 是裸片段，这也是为什么"夹紧"不是可选优化）。
        long offset = (long) (q.getPage() - 1) * q.getSize();
        w.last("LIMIT " + q.getSize() + " OFFSET " + offset);

        List<Map<String, Object>> items = new ArrayList<>();
        for (T e : mapper().selectList(w)) {
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

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    /** 按主键取一行全部列；不存在返回 null。 */
    public Map<String, Object> getById(int id) {
        T e = mapper().selectById(id);
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
        if (mapper().selectById(id) == null) {
            return null;
        }
        ColumnRegistry reg = registry();
        T patch = newPatch();
        ColumnRegistry.Column pk = reg.byName(reg.primaryKeyName());
        reg.set(pk, patch, id);
        List<String> touched = new ArrayList<>();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String column = e.getKey();
            ColumnRegistry.Column col = reg.byName(column);
            if (col == null) {
                throw new IllegalArgumentException("未知的列名：" + column);
            }
            if (col.primaryKey()) {
                throw new IllegalArgumentException("主键 " + column + " 不允许修改");
            }
            if (!col.editable()) {
                throw new IllegalArgumentException("列 " + column + " 不允许修改");
            }
            reg.set(col, patch, coerce(col, e.getValue()));
            touched.add(column);
        }
        // MyBatis-Plus 默认忽略 null ⇒ 只写了请求体里的列，其余保持原值（部分更新）
        mapper().updateById(patch);
        // 一行 INFO 记录"谁改了哪几列"（不含旧值 —— 复用/回滚不是本期目标，见设计文档 §5.4）
        log.info("{} 修改{} id={} 列={}", logTag(), entityLabel(), id, touched);
        return toRow(mapper().selectById(id));
    }

    /** 把 JSON 里的值转成实体字段的类型；转换不过一律拒绝，不做"猜"式兜底。 */
    private Object coerce(ColumnRegistry.Column col, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("列 " + col.name() + " 不支持传 null 清空（当前语义：null = 不改）");
        }
        Class<?> type = col.type();
        String what = "列 " + col.name();
        if (type == String.class) {
            if (!(value instanceof String s)) {
                throw new IllegalArgumentException(what + " 需要字符串，收到 " + value.getClass().getSimpleName());
            }
            return s;
        }
        // 基本类型转换走共用实现（JsonValues）——掉落表的整表保存要用同一套
        if (type == Integer.class) {
            return JsonValues.toInt(value, what);
        }
        if (type == Double.class) {
            return JsonValues.toDouble(value, what);
        }
        if (type == Boolean.class) {
            return JsonValues.toBool(value, what);
        }
        throw new IllegalStateException("未支持的字段类型：" + type.getName() + "（列 " + col.name() + "）");
    }

    // ------------------------------------------------------------------
    // 行转换与筛选辅助（子类 applyFilters 用）
    // ------------------------------------------------------------------

    /** 实体 → `Map<列名, 值>`，键**按段顺序**（与 /columns 一致），**包含全部列**（含主键）。 */
    protected Map<String, Object> toRow(T e) {
        ColumnRegistry reg = registry();
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnRegistry.Column col : reg.orderedColumns()) {
            row.put(col.name(), reg.get(col, e));
        }
        return row;
    }

    /** 单列区间：只给一端也生效（"至少 N" / "至多 N"）。 */
    protected static <T> void range(QueryWrapper<T> w, String column, Integer min, Integer max) {
        if (min != null) {
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
    protected static <T> void overlap(QueryWrapper<T> w, String minColumn, String maxColumn,
                                      Integer lowerBound, Integer upperBound) {
        if (lowerBound != null) {
            w.ge(maxColumn, lowerBound);
        }
        if (upperBound != null) {
            w.le(minColumn, upperBound);
        }
    }

    /** 把 LIKE 的通配符转成字面量（PG 的默认转义符是反斜杠）。 */
    protected static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
