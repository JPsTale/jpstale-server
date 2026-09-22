package org.jpstale.server.web.admin;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 一张表的**列注册表**：列名 ⇄ 实体属性的唯一映射 + 归属段 + 可改性 + 可筛性。
 *
 * <p>
 * 为什么要有它：管理端接口的对外口径是"**数据库列名**"，而实体是驼峰。这个映射原先各处手写
 * （一个字段一行 setter），加一列要记得改多处。这里改成**反射一次性生成**，于是：
 * <ul>
 *   <li>"全部列"是结构保证的，不是"我记得的那些列"；</li>
 *   <li>实体加/删字段时，接口自动跟上。</li>
 * </ul>
 *
 * <p>
 * ⚠ 只认**真列**：`@TableField(exist = false)` 的字段一律跳过 —— 它们不在库里，出现在接口里就是谎报。
 *
 * <p>
 * 段归属由**各表自己**给（{@code sectionOf}）；本类做**一次性自检**：未归类的列不会消失，
 * 而是落进 {@link #SECTION_UNASSIGNED} 段**并在日志里报出来** —— 免得"某列在界面上没有"
 * 这种症状静默存在（那正是本项目最忌讳的失败方式）。
 *
 * <p>
 * ⚠ 物品与怪物**共用本类**（各自 new 一个实例）。抄第二份反射逻辑的下场是：一处修了 bug、
 * 另一处留着（AGENTS #15）。
 */
public final class ColumnRegistry {

    /** 未归类段：只在自检发现漏列时才会出现（正常情况下列表里没有它）。 */
    public static final String SECTION_UNASSIGNED = "Unassigned";

    /**
     * **不可手动改**的列（审计/时间戳类）。
     *
     * <p>
     * ⚠ 用**精确列名**匹配，**禁止**改成 `*time` / `*_at` 之类的后缀通配：
     * 物品表有一列 {@code questflashingtime}（原版物品的**闪烁时间**）是业务字段，
     * 后缀通配会把它一起排除，而"某列改不动"这种症状很难查。
     */
    public static final Set<String> NOT_EDITABLE_COLUMNS = Set.of(
            "create_time", "update_time", "delete_time", "created_at", "updated_at");

    /** 一列的全部元信息。`field` 不对外（接口只给列名/类型名），供本包内读写用。 */
    public record Column(String name, String property, Class<?> type, Field field,
                         String section, boolean primaryKey, boolean editable, boolean filterable) {
    }

    private final Logger log;
    private final Map<String, Column> byName;
    private final Map<String, List<Column>> bySection;
    private final List<Column> ordered;
    private final String primaryKeyName;
    private final String labelKeyPrefix;

    /**
     * @param entity         实体类（认它的 `@TableId`/`@TableField`）
     * @param sectionOrder   段的展示顺序；不在其中的列落 {@link #SECTION_UNASSIGNED}
     * @param sectionOf      列名 → 段 id（**加列时只改这里**）
     * @param filterable     参与筛选的列
     * @param labelKeyPrefix 段名文案 key 的前缀（如 `admin.section.`），后接段 id
     * @param logTag         日志前缀（如 `ItemColumn` / `MonsterColumn`）
     */
    public ColumnRegistry(Class<?> entity, List<String> sectionOrder, Function<String, String> sectionOf,
                          Set<String> filterable, String labelKeyPrefix, String logTag) {
        this.log = LoggerFactory.getLogger(ColumnRegistry.class);
        this.labelKeyPrefix = labelKeyPrefix;

        List<String> sections = new ArrayList<>(sectionOrder);
        sections.add(SECTION_UNASSIGNED);
        Map<String, List<Column>> sectionsMap = new LinkedHashMap<>();
        sections.forEach(s -> sectionsMap.put(s, new ArrayList<>()));

        Map<String, Column> nameMap = new LinkedHashMap<>();
        String pk = null;
        for (Field f : entity.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            TableId tableId = f.getAnnotation(TableId.class);
            TableField tableField = f.getAnnotation(TableField.class);
            String column;
            if (tableId != null) {
                column = tableId.value();
            } else if (tableField != null) {
                if (!tableField.exist()) {
                    continue;   // exist=false ⇒ 不是库里的列，不能出现在接口里
                }
                column = tableField.value();
            } else {
                continue;       // 没有映射注解的字段不是列
            }
            if (column == null || column.isBlank()) {
                continue;
            }
            f.setAccessible(true);
            boolean primaryKey = tableId != null;
            if (primaryKey) {
                pk = column;
            }
            Column col = new Column(column, f.getName(), f.getType(), f,
                    sectionOf.apply(column), primaryKey, !primaryKey && !NOT_EDITABLE_COLUMNS.contains(column),
                    filterable.contains(column));
            nameMap.put(column, col);
            sectionsMap.get(col.section()).add(col);
        }

        // 自检：段归属必须与列集合一致。漏列不静默 —— 落进 Unassigned 段并在日志里报出来。
        for (Column col : nameMap.values()) {
            if (col.section().equals(SECTION_UNASSIGNED)) {
                log.error("[{}] 列 {} 没有段归属，已放入「未归类」段 —— 请把它加进分段表", logTag, col.name());
            }
        }
        Set<String> declared = new LinkedHashSet<>();
        sectionsMap.values().forEach(list -> list.forEach(c -> declared.add(c.name())));
        if (declared.size() != nameMap.size()) {
            log.error("[{}] 分段表里出现了库里没有的列：{}（分段表与实体不一致）", logTag,
                    declared.stream().filter(n -> !nameMap.containsKey(n)).toList());
        }
        if (sectionsMap.get(SECTION_UNASSIGNED).isEmpty()) {
            sectionsMap.remove(SECTION_UNASSIGNED);
        }
        if (pk == null) {
            // 没有主键就没法做"详情/改一行"，这是启动期就该知道的结构错误
            log.error("[{}] 实体 {} 没有 @TableId 列 —— 详情与修改接口无法工作", logTag, entity.getSimpleName());
        }
        this.primaryKeyName = pk;

        List<Column> order = new ArrayList<>(nameMap.size());
        sectionsMap.values().forEach(order::addAll);
        this.ordered = List.copyOf(order);
        this.byName = Collections.unmodifiableMap(nameMap);
        this.bySection = Collections.unmodifiableMap(sectionsMap);
        log.info("[{}] 列注册表就绪：{} 列，{} 段（{}）", logTag, byName.size(), bySection.size(),
                bySection.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).toList());
    }

    /**
     * 全部列，**按段顺序**（段序由构造时的 `sectionOrder` 决定），段内保持实体声明顺序。
     *
     * <p>
     * ⚠ 这是**唯一**的列顺序：接口的列清单与数据行的键序都走它。早先按实体声明顺序输出过一次，
     * 结果物品的 `quest*`（Misc）在实体里靠前，"其他"段跑到了第二位 —— 段序必须由段表决定，
     * 不能由实体的字段顺序决定。
     */
    public List<Column> orderedColumns() {
        return ordered;
    }

    public Column byName(String column) {
        return byName.get(column);
    }

    public boolean has(String column) {
        return byName.containsKey(column);
    }

    public int size() {
        return byName.size();
    }

    /** 主键列名（详情/修改都用它定位一行）。 */
    public String primaryKeyName() {
        return primaryKeyName;
    }

    /** 段名的文案 key（服务端只给 key，文案在页面侧）。 */
    public String sectionLabelKey(String section) {
        return labelKeyPrefix + section;
    }

    /** 读一列的值。 */
    public Object get(Column col, Object entity) {
        try {
            return col.field().get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读列失败: " + col.name(), e);
        }
    }

    /** 写一列的值为**已校验过类型**的对象。 */
    public void set(Column col, Object entity, Object value) {
        try {
            col.field().set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写列失败: " + col.name(), e);
        }
    }
}
