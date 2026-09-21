package org.jpstale.server.web.item;

import org.jpstale.dao.gamedb.entity.ItemList;
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

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * `gamedb.itemlist` 的**列注册表**：列名 ⇄ 实体属性 的唯一映射 + 归属段 + 可改性 + 可筛性。
 *
 * <p>
 * 为什么要有它：物品管理的对外口径是"**数据库列名**"（设计文档 §二），而实体是驼峰。
 * 这个映射原先只在各处的 `SimulatorService.toSummary/toDetail` 里手写（一个字段一行 setter），
 * 加一列要记得改多处。这里改成**反射一次性生成**，于是：
 * <ul>
 *   <li>"全部列"是结构保证的，不是"我记得的那些列"；</li>
 *   <li>实体加/删字段（例如按 AGENTS #8(d) 重扫 OpenPKG 补两列）时，接口自动跟上。</li>
 * </ul>
 *
 * <p>
 * ⚠ 只认**真列**：`@TableField(exist = false)` 的字段（当前是 {@code UserInfo.webAdmin} 那种"影子字段"）
 * 一律跳过 —— 它们不在库里，出现在接口里就是谎报。
 *
 * <p>
 * 段归属（设计文档 §三，五段）在这里硬编码；`static` 块做**一次性自检**：
 * 未归类的列不会消失，而是落进 {@link #SECTION_UNASSIGNED} 段**并在日志里报出来** ——
 * 免得"某列在界面上没有"这种症状静默存在（那正是本项目最忌讳的失败方式）。
 */
public final class ItemColumnRegistry {

    private static final Logger log = LoggerFactory.getLogger(ItemColumnRegistry.class);

    /** 段的展示顺序。 */
    public static final List<String> SECTION_ORDER =
            List.of("Identity", "Requirements", "BaseStats", "Spec", "Misc");

    /** 未归类段：只在自检发现漏列时才会出现（正常情况下列表里没有它）。 */
    public static final String SECTION_UNASSIGNED = "Unassigned";

    /**
     * 段的**文案 key**（文案在页面侧 `static/i18n/{zh,en}.json`）。
     *
     * <p>
     * 用户 2026-09-21 要求**不许硬编码文案**（"我的 web 页面后面怎么搞 i18n"）——
     * 所以服务端一律只给 key，与 {@code Result.msg} 的既有约定一致。
     */
    public static final Map<String, String> SECTION_LABEL_KEYS = Map.of(
            "Identity", "admin.section.Identity",
            "Requirements", "admin.section.Requirements",
            "BaseStats", "admin.section.BaseStats",
            "Spec", "admin.section.Spec",
            "Misc", "admin.section.Misc",
            SECTION_UNASSIGNED, "admin.section.Unassigned");
    /**
     * **不可手动改**的列。
     *
     * <p>
     * ⚠ 用**精确列名**匹配，**禁止**改成 `*time` / `*_at` 之类的后缀通配：
     * 本表有一列 {@code questflashingtime}（原版物品的**闪烁时间**）是业务字段，
     * 后缀通配会把它一起排除，而"某列改不动"这种症状很难查。
     */
    private static final Set<String> NOT_EDITABLE_COLUMNS = Set.of(
            "create_time", "update_time", "delete_time", "created_at", "updated_at");

    /** 基础属性的"成对区间"列基名（每对 = `*min` + `*max`）。 */
    private static final List<String> BASE_PAIR_STATS = List.of(
            "integrity", "atkpow1", "atkpow2", "atkrating", "block", "absorb", "defense", "runspeed",
            "organic", "fire", "frost", "lightning", "poison",
            "addhp", "addmp", "addstm",
            "regenerationhp", "regenerationmp", "regenerationstm",
            "recoveryhp", "recoverymp", "recoverystm");

    /** 基础属性里的单值列。 */
    private static final List<String> BASE_SINGLES = List.of(
            "atkspeed", "critical", "range", "potionspace", "potioncount", "weight", "price");

    /** 参与筛选的列（设计文档 §5.2 的四组条件；`name` 走模糊，其余见 AdminItemService）。 */
    private static final Set<String> FILTERABLE = Set.of(
            "name", "idcode", "category",
            "reqlevel", "price", "weight",
            "weaponclass", "classitem", "modelposition",
            "defensemin", "defensemax", "atkpow1min", "atkpow1max", "atkpow2min", "atkpow2max",
            "atkratingmin", "atkratingmax", "absorbmin", "absorbmax", "blockmin", "blockmax");

    /** 一列的全部元信息。`field` 不对外（接口只给列名/类型名），供本包内读写用。 */
    public record Column(String name, String property, Class<?> type, Field field,
                         String section, boolean primaryKey, boolean editable, boolean filterable) {
    }

    /** 列名 → 列。按实体声明顺序保序。 */
    private static final Map<String, Column> BY_NAME;

    /** 段 → 该段的列（保序）。 */
    private static final Map<String, List<Column>> BY_SECTION;

    static {
        List<String> sections = new ArrayList<>(SECTION_ORDER);
        sections.add(SECTION_UNASSIGNED);
        Map<String, List<Column>> bySection = new LinkedHashMap<>();
        sections.forEach(s -> bySection.put(s, new ArrayList<>()));

        Map<String, Column> byName = new LinkedHashMap<>();
        for (Field f : ItemList.class.getDeclaredFields()) {
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
            boolean editable = !primaryKey && !NOT_EDITABLE_COLUMNS.contains(column);
            Column col = new Column(column, f.getName(), f.getType(), f,
                    sectionOf(column), primaryKey, editable, FILTERABLE.contains(column));
            byName.put(column, col);
            bySection.get(col.section()).add(col);
        }

        // 自检：段归属必须与列集合一致。漏列不静默 —— 落进 Unassigned 段并在日志里报出来。
        for (Column col : byName.values()) {
            if (col.section().equals(SECTION_UNASSIGNED)) {
                log.error("[ItemColumn] 列 {} 没有段归属，已放入「未归类」段 —— 请把它加进 ItemColumnRegistry 的分段表", col.name());
            }
        }
        Set<String> declared = new LinkedHashSet<>();
        sections.forEach(s -> bySection.get(s).forEach(c -> declared.add(c.name())));
        if (declared.size() != byName.size()) {
            log.error("[ItemColumn] 分段表里出现了库里没有的列：{}（分段表与实体不一致）",
                    declared.stream().filter(n -> !byName.containsKey(n)).toList());
        }
        if (bySection.get(SECTION_UNASSIGNED).isEmpty()) {
            bySection.remove(SECTION_UNASSIGNED);
        }

        BY_NAME = Collections.unmodifiableMap(byName);
        BY_SECTION = Collections.unmodifiableMap(bySection);
        log.info("[ItemColumn] 列注册表就绪：{} 列，{} 段（{}）", BY_NAME.size(), BY_SECTION.size(),
                BY_SECTION.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).toList());
    }

    /** 段归属表。加列时**只需改这里**；漏改的列会落进 Unassigned 并报错。 */
    private static String sectionOf(String column) {
        if (List.of("id", "idcode", "name", "category", "weaponclass", "classitem", "modelposition",
                "width", "height", "sound", "codeimg1", "codeimg2", "dropfolder").contains(column)) {
            return "Identity";
        }
        if (List.of("reqlevel", "reqstrength", "reqspirit", "reqtalent", "reqagility", "reqhealth").contains(column)) {
            return "Requirements";
        }
        if (BASE_SINGLES.contains(column)) {
            return "BaseStats";
        }
        for (String base : BASE_PAIR_STATS) {
            if (column.equals(base + "min") || column.equals(base + "max")) {
                return "BaseStats";
            }
        }
        if (column.equals("primaryspec")) {
            return "Spec";
        }
        for (int i = 1; i <= 12; i++) {
            if (column.equals("addspecclass" + i)) {
                return "Spec";
            }
        }
        if (List.of("addspecrunspeedmin", "addspecrunspeedmax", "addspecabsorbmin", "addspecabsorbmax",
                "addspecdefensemin", "addspecdefensemax", "addspecatkspeed", "addspeccritical",
                "addspecatkpowermin", "addspecatkpowermax", "addspecatkratingmin", "addspecatkratingmax",
                "addspechpregen", "addspecmpregenmin", "addspecmpregenmax", "addspecstmregen",
                "addspecblock", "addspecrange").contains(column)) {
            return "Spec";
        }
        if (List.of("questid", "questr", "questg", "questb", "questtransparency",
                "questflashingtime", "cannotdrop").contains(column)) {
            return "Misc";
        }
        return SECTION_UNASSIGNED;
    }

    private ItemColumnRegistry() {
    }

    /**
     * 全部列，**按段顺序**（身份 → 需求 → 基础属性 → 职业特效 → 其他），段内保持实体声明顺序。
     *
     * <p>
     * ⚠ 这是**唯一**的列顺序：接口的列清单与数据行的键序都走它。早先按实体声明顺序输出过一次，
     * 结果 `quest*`（Misc）在实体里靠前，"其他"段跑到了第二位 —— 段序必须由
     * {@link #SECTION_ORDER} 决定，不能由实体的字段顺序决定。
     */
    public static List<Column> orderedColumns() {
        List<Column> out = new ArrayList<>(BY_NAME.size());
        BY_SECTION.values().forEach(out::addAll);
        return List.copyOf(out);
    }

    public static Column byName(String column) {
        return BY_NAME.get(column);
    }

    public static boolean has(String column) {
        return BY_NAME.containsKey(column);
    }

    public static int size() {
        return BY_NAME.size();
    }

    /** 读一列的值。 */
    public static Object get(Column col, Object entity) {
        try {
            return col.field().get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读列失败: " + col.name(), e);
        }
    }

    /** 写一列的值为**已校验过类型**的对象。 */
    public static void set(Column col, Object entity, Object value) {
        try {
            col.field().set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写列失败: " + col.name(), e);
        }
    }
}
