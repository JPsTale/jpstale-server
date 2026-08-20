package org.jpstale.server.web.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品代码前缀分类工具（wartale 风格三级分类）。
 * <p>
 * DB gamedb.itemlist.idcode 的高 16 位（idcode &amp; 0xFFFF0000）为物品类别前缀。
 * 分类命名遵循 wartale（user.wartale.com）：
 * - 顶层 Weapons | Defenses | Accessories
 * - 二级 Axes/Bows/Claws/... | Armors/Robes/... | Amulets/Rings/...
 */
public final class ItemCategory {

    private static final Map<Long, Category> BY_PREFIX = new LinkedHashMap<>();

    static {
        // --- Weapons ---
        put(0x01010000L, "Axes", "Weapons");
        put(0x01020000L, "Claws", "Weapons");
        put(0x01030000L, "Hammers", "Weapons");
        put(0x01040000L, "Wands & Staffs", "Weapons");
        put(0x01050000L, "Scythes", "Weapons");
        put(0x01060000L, "Bows", "Weapons");
        put(0x01070000L, "Swords", "Weapons");
        put(0x01080000L, "Javelins", "Weapons");
        put(0x01090000L, "Phantoms", "Weapons");
        put(0x010A0000L, "Daggers", "Weapons");
        // --- Defenses ---
        put(0x02010000L, "Armors", "Defenses");
        put(0x02020000L, "Boots", "Defenses");
        put(0x02030000L, "Gauntlets", "Defenses");
        put(0x02040000L, "Shields", "Defenses");
        put(0x02050000L, "Robes", "Defenses");
        put(0x02060000L, "Armors", "Defenses");
        put(0x02070000L, "Robes", "Defenses");
        put(0x02100000L, "Armors", "Defenses");
        put(0x02110000L, "Armors", "Defenses");
        put(0x02350000L, "Sheltoms", "Accessories");
        put(0x03020000L, "Bracelets", "Defenses");
        put(0x03030000L, "Orbs", "Defenses");
        // --- Accessories ---
        put(0x03010000L, "Amulets", "Accessories");
        put(0x03040000L, "Rings", "Accessories");
        put(0x03050000L, "Rings", "Accessories");
        put(0x03060000L, "Sheltoms", "Accessories");
    }

    private static void put(long prefix, String subtype, String type) {
        BY_PREFIX.put(prefix, new Category(subtype, type));
    }

    /**
     * 根据 idcode 反查分类。未知前缀返回 null。
     */
    public static Category of(int idCode) {
        return BY_PREFIX.get((long) (idCode & 0xFFFF0000));
    }

    /**
     * wartale 顶层分类 → 子类顺序（含空子类，用于展示全部导航项）。
     */
    public static Map<String, List<String>> tree() {
        Map<String, List<String>> tree = new LinkedHashMap<>();
        tree.put("Weapons", List.of(
                "Axes", "Bows", "Claws", "Daggers", "Fists", "Hammers",
                "Javelins", "Phantoms", "Scythes", "Swords", "Wands & Staffs"));
        tree.put("Defenses", List.of(
                "Armors", "Robes", "Shields", "Orbs", "Bracelets", "Gauntlets", "Boots"));
        tree.put("Accessories", List.of(
                "Amulets", "Belts", "Earrings", "Rings", "Sheltoms"));
        return tree;
    }

    public static final class Category {
        private final String subtype;
        private final String type;

        Category(String subtype, String type) {
            this.subtype = subtype;
            this.type = type;
        }

        public String getSubtype() {
            return subtype;
        }

        public String getType() {
            return type;
        }

        /** 兼容旧接口：分类名（子类） */
        public String getName() {
            return subtype;
        }

        /** 兼容旧接口：分组（顶层） */
        public String getGroup() {
            return type;
        }
    }
}