package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.MixList;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条合成配方（`gamedb.mixlist` 一行的内存形态）。
 *
 * <p>
 * 列义（实测我们的库，见 `docs/合成配方全表.md`）：
 * <ul>
 *   <li>材料 = 14 列石头个数（`lucidy`…`oredo` = 档位 1..14 = `sinOS1 | (tier&lt;&lt;8)`）；</li>
 *   <li>效果 = **8** 组 `(typeatributte, atributte, peratributte)` —— 位 / 值 / 加法类型（见 {@link MixEffect.Kind}）。</li>
 *   <li>{@code mixuniqueid} = 配方 id —— 合成后写进物品的 `aging_num2`（EU 的 `sMixUniqueID1` 那一格）；</li>
 *   <li>{@code typemix} = 基材类（{@link MixType#fromCode}）。</li>
 * </ul>
 *
 * <p>
 * ⚠ **材料数是"逐个严格相等"**（EU `MixHandler.cpp:483-500`：任一档不等即跳过该配方），不是"至少"。
 */
public final class MixRecipe {

    /** 材料槽数（= `mixlist` 的 14 个石头列；与 §3.13.4 的 `iaSheltomAgingList[..][12]` 不是同一张表）。 */
    public static final int STONE_SLOTS = 14;
    /**
     * 效果槽数 = **8**（`typeatributte` + `typeatributte2..8`）。
     * ⚠ 我第一版只读了 6 组，**静默丢掉**第 7 槽 —— 实测有 **12 条**配方用了第 7 槽
     * （`mixuniqueid` 603/608/…，位 0x10 生体抗 +1/+2/+3，描述里的 "Resistances +N"），
     * 是核对列清单时发现的（`docs/合成配方全表.md` 的"6 组"同样错）。第 8 槽实测全为 0，但一并读，免得再来一次。
     */
    public static final int EFFECT_SLOTS = 8;

    /** 一个效果槽。 */
    public record Slot(int bit, double value, int kind) {
        /** 该槽是否有效（位为 0 = 空槽）。 */
        public boolean used() {
            return bit != 0;
        }
    }

    private final int dbId;
    private final int uniqueId;
    private final MixType type;
    private final String typeName;
    private final String description;
    /** 14 档石头的需求个数，下标 0 = 档位 1（Lucidy）。 */
    private final int[] stones;
    private final List<Slot> slots;

    private MixRecipe(int dbId, int uniqueId, MixType type, String typeName, String description,
                      int[] stones, List<Slot> slots) {
        this.dbId = dbId;
        this.uniqueId = uniqueId;
        this.type = type;
        this.typeName = typeName;
        this.description = description;
        this.stones = stones;
        this.slots = slots;
    }

    /** 从 `mixlist` 行构造（列名 → 语义的对应写在这里，**一处**）。 */
    public static MixRecipe of(MixList row) {
        int[] stones = new int[STONE_SLOTS];
        stones[0] = nz(row.getLucidy());
        stones[1] = nz(row.getSereneo());
        stones[2] = nz(row.getFadeo());
        stones[3] = nz(row.getSparky());
        stones[4] = nz(row.getRaident());
        stones[5] = nz(row.getTransparo());
        stones[6] = nz(row.getMurky());
        stones[7] = nz(row.getDevine());
        stones[8] = nz(row.getCelesto());
        stones[9] = nz(row.getMirage());
        stones[10] = nz(row.getInferna());
        stones[11] = nz(row.getEnigma());
        stones[12] = nz(row.getBellum());
        stones[13] = nz(row.getOredo());
        List<Slot> slots = new ArrayList<>(EFFECT_SLOTS);
        slots.add(slot(row.getTypeAtributte(), row.getAtributte(), row.getPerAtributte()));
        slots.add(slot(row.getTypeAtributte2(), row.getAtributte2(), row.getPerAtributte2()));
        slots.add(slot(row.getTypeAtributte3(), row.getAtributte3(), row.getPerAtributte3()));
        slots.add(slot(row.getTypeAtributte4(), row.getAtributte4(), row.getPerAtributte4()));
        slots.add(slot(row.getTypeAtributte5(), row.getAtributte5(), row.getPerAtributte5()));
        slots.add(slot(row.getTypeAtributte6(), row.getAtributte6(), row.getPerAtributte6()));
        slots.add(slot(row.getTypeAtributte7(), row.getAtributte7(), row.getPerAtributte7()));
        slots.add(slot(row.getTypeAtributte8(), row.getAtributte8(), row.getPerAtributte8()));
        return new MixRecipe(nz(row.getId()), nz(row.getMixUniqueId()),
                MixType.fromCode(row.getTypeMix()), row.getTypeMixName(), row.getDescription(), stones, slots);
    }

    private static Slot slot(Integer bit, Double value, Integer kind) {
        return new Slot(nz(bit), value == null ? 0 : value, kind == null ? MixEffect.Kind.FLAT : kind);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 石头个数是否符合本配方（**逐个严格相等**，照抄 EU）。 */
    public boolean matchesStones(int[] have) {
        for (int i = 0; i < STONE_SLOTS; i++) {
            if (stones[i] != (i < have.length ? have[i] : 0)) {
                return false;
            }
        }
        return true;
    }

    /** 本配方要求的石头总数（EU 先用它做粗筛；我们保留只为日志/诊断）。 */
    public int totalStones() {
        int n = 0;
        for (int s : stones) {
            n += s;
        }
        return n;
    }

    public int dbId() {
        return dbId;
    }

    public int uniqueId() {
        return uniqueId;
    }

    public MixType type() {
        return type;
    }

    public String typeName() {
        return typeName;
    }

    public String description() {
        return description;
    }

    public int stoneAt(int index) {
        return stones[index];
    }

    public List<Slot> slots() {
        return slots;
    }

    /** 参与的效果位（非空槽）。 */
    public int mask() {
        int mask = 0;
        for (Slot s : slots) {
            if (s.used()) {
                mask |= s.bit();
            }
        }
        return mask;
    }
}
