package org.jpstale.server.web.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.web.admin.ColumnRegistry;

import java.util.List;
import java.util.Set;

/**
 * `gamedb.itemlist` 的列注册表 —— 只放**物品特有的部分**（段顺序、段归属、可筛列），
 * 反射与自检逻辑在共用的 {@link ColumnRegistry} 里（物品与怪物同一份实现，AGENTS #15）。
 *
 * <p>
 * "全部列"由反射保证：实体加/删字段时接口自动跟上，不必手抄列清单。
 *
 * @see ColumnRegistry
 */
public final class ItemColumnRegistry {

    /** 段的展示顺序。 */
    public static final List<String> SECTION_ORDER =
            List.of("Identity", "Requirements", "BaseStats", "Spec", "Misc");

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

    /** 反射生成的列注册表（段名文案 key 前缀 `admin.section.`，日志前缀 `ItemColumn`）。 */
    public static final ColumnRegistry REGISTRY = new ColumnRegistry(
            ItemList.class, SECTION_ORDER, ItemColumnRegistry::sectionOf, FILTERABLE,
            "admin.section.", "ItemColumn");

    /** 段归属表。加列时**只需改这里**；漏改的列会落进 Unassigned 并在日志里报错。 */
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
        return ColumnRegistry.SECTION_UNASSIGNED;
    }

    private ItemColumnRegistry() {
    }
}
