package org.jpstale.common.service.item;

/**
 * 合成（Mix）的**基材类** —— 对应原版 `EMixType` / 我们 `gamedb.mixlist.typemix` 的整数。
 *
 * <p>
 * 判定照抄 EU `Server/server/MixHandler.cpp:1548-1588` 的 `GetTypeMixByItemCode()`：
 * <pre>
 *   (idcode &amp; 0xFF000000) == ITEMBASE_Weapon  (0x01) → Weapons
 *   (idcode &amp; 0xFF000000) == ITEMBASE_Defense (0x02) → ArmourRobe（默认）
 *       家族 0x0204 Shield    → Sheilds
 *       家族 0x0203 Gauntlets → Gauntlets
 *       家族 0x0202 Boots     → Boots
 *   (idcode &amp; 0xFF000000) == ITEMBASE_Sheltom(0x03) →
 *       0x0303 Orb → Orbs ／ 0x0302 Bracelets → Bracelets ／ 0x0301 Amulet → Amulets ／ 0x0304/0x0305 Ring → Rings
 * </pre>
 * ⚠ 两个与我们数据有关的点：
 * <ul>
 *   <li><b>拳套（WV，0x010B）天然落进 `Weapons`</b>（base 字节 0x01）—— 与用户 2026-09-22 "拳套当爪处理" 一致；</li>
 *   <li>**材料石（OS 族 0x0235）**的 base 字节是 **0x02**，EU 那套会把它们算成 `ArmourRobe` ✗ ——
 *       它们不可能当合成目标（是投进去的材料），故这里**显式排除**（{@link #UNKNOWN}），不做隐式兜底。</li>
 * </ul>
 * `typemix` 的取值实测（`gamedb.mixlist`，283 条配方）：1 Weapons / 2 ArmourRobe / 3 Sheilds / 4 Orbs /
 * 5 Bracelets / 6 Gauntlets / 7 Boots —— 与上面的枚举顺序一致，故 {@link #code()} 直接就是那列的值。
 */
public enum MixType {
    /** 武器（WA/WC/WH/WM/WP/WS/WT/WN/WD，以及 11 职业新增的 WV 拳套） */
    WEAPONS(1, "Weapons"),
    /** 甲 / 法袍（含 base 0x02 里除盾/护手/靴之外的一切） */
    ARMOUR_ROBE(2, "ArmourRobe"),
    /** 盾 */
    SHIELDS(3, "Sheilds"),
    /** 法球 */
    ORBS(4, "Orbs"),
    /** 护腕 */
    BRACELETS(5, "Bracelets"),
    /** 护手 */
    GAUNTLETS(6, "Gauntlets"),
    /** 靴 */
    BOOTS(7, "Boots"),
    /** 不可合成（材料石本身、或我们的配方表未覆盖的类型，如戒指/项链） */
    UNKNOWN(0, "");

    private final int code;
    private final String label;

    MixType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** `gamedb.mixlist.typemix` 列的取值。 */
    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 配方行 → 类型；未知取值返回 {@link #UNKNOWN}（调用方须报出来，见 `MixRecipeService`）。 */
    public static MixType fromCode(Integer typeMix) {
        if (typeMix == null) {
            return UNKNOWN;
        }
        for (MixType t : values()) {
            if (t != UNKNOWN && t.code == typeMix) {
                return t;
            }
        }
        return UNKNOWN;
    }

    /** 物品 idcode → 可合成类型（照抄 EU `GetTypeMixByItemCode`，见类注释）。 */
    public static MixType ofItemCode(Integer idCode) {
        if (idCode == null || idCode == 0) {
            return UNKNOWN;
        }
        int base = idCode & 0xFF000000;
        int family = idCode & 0xFFFF0000;
        // 材料石（OS 族）：base 字节是 0x02，但它是"投进去的东西"不是"被合成的装备" ⇒ 显式排除
        if (family == 0x02350000) {
            return UNKNOWN;
        }
        if (base == 0x01000000) {                    // ITEMBASE_Weapon
            return WEAPONS;
        }
        if (base == 0x02000000) {                    // ITEMBASE_Defense
            if (family == 0x02040000) {              // ITEMTYPE_Shield (sinDS1)
                return SHIELDS;
            }
            if (family == 0x02030000) {              // ITEMTYPE_Gauntlets (sinDG1)
                return GAUNTLETS;
            }
            if (family == 0x02020000) {              // ITEMTYPE_Boots (sinDB1)
                return BOOTS;
            }
            return ARMOUR_ROBE;                      // 甲 0x0201 / 法袍 0x0205 / DA3 0x0212 / DA4 0x0213 …
        }
        if (base == 0x03000000) {                    // ITEMBASE_Sheltom（饰品/法球这一支）
            if (family == 0x03030000) {              // ITEMTYPE_Orb (sinOM1)
                return ORBS;
            }
            if (family == 0x03020000) {              // ITEMTYPE_Bracelets (sinOA2)
                return BRACELETS;
            }
            // 项链 0x0301 / 戒指 0x0304·0x0305：EU 有对应类型，但**我们的配方表没有这两类配方**
            // （283 条只覆盖上面 7 类）⇒ 返回 UNKNOWN，不假装可合成（拿不到配方自然合不了）。
            return UNKNOWN;
        }
        return UNKNOWN;
    }
}
