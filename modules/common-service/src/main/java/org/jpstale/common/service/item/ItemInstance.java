package org.jpstale.common.service.item;

import lombok.Data;
import org.jpstale.dao.gamedb.entity.ItemList;

/**
 * 物品实例（内存态）：掷点结果 + 归属位置 + 模板引用。
 * <p>
 * 与 userdb.item 行一一对应；uid 即 DB id（未落库前为 null）。
 * 位置用 (location, slot) 表达（见 {@link ItemLocations}）。
 */
@Data
public class ItemInstance {

    // ==================================================================
    // 派生属性（锻造 / 合成）—— 2026-09-22
    //
    // **基准**（下面那些 private 字段）= 数据库里存的值，持久化读写它，这条重构**没有动它**。
    // **加成** = 锻造等级 N + 合成配方 id 决定的增量，只活在内存里（`statMod`），读时按需重算。
    // **有效值** = 基准 + 加成 —— 需要"生效属性"的地方（角色属性聚合、下发客户端）用
    //              `effective(...)` / `effectiveInt(...)`；**别覆盖原 getter**（持久化也用它，见 ItemStat 注释）。
    // ==================================================================

    /**
     * 合成的**效果位掩码**（= 配方里那些效果位的并集）—— **派生值**，由配方 id 算出来（不落库）。
     *
     * <p>原版 `ItemKindMask` 的唯一用途是**显示**：客户端工具提示逐位判 `SIN_ADD_*` 来标记
     * "哪几行是合成加上去的"（ex-machina `sinItem.cpp:2345-2516`；我们给锻造行染色是同一件事）。
     * 而 EU 那边连配方效果都是**按 id 反推**的（`SetItemMixByUniqueID(psItemNew, psItemOld->sMixUniqueID1)`、
     * `UIItemInfoBox.cpp:5326` 的 `vMixSheltoms.find(sMixUniqueID1)`）⇒ 掩码可由 `agingNum2` 唯一确定，
     * 没有存的必要（用户 2026-09-22 指出："aging_num 不能表示配方的唯一性吗？"—— 能）。
     * ⚠ `userdb.item.craft_mask` 这一列**保留但不再由我们写入**（历史数据用；读取侧一律用本方法）。
     */
    private transient int derivedCraftMask;

    /**
     * 合成配方的**效果清单**（`MixEffect.Applied`：位/文案 key/值/是否直加）—— 派生值，不落库。
     * 客户端拿它拼"配方显示名"（`t(key)` 查 `mixe.*`，只发数据不发文案）。
     */
    private transient java.util.List<MixEffect.Applied> derivedMixEffects = java.util.List.of();

    public java.util.List<MixEffect.Applied> getDerivedMixEffects() {
        ensureDerived();
        return derivedMixEffects;
    }

    public void setDerivedMixEffects(java.util.List<MixEffect.Applied> list) {
        this.derivedMixEffects = list == null ? java.util.List.of() : list;
    }

    public int getDerivedCraftMask() {
        ensureDerived();
        return derivedCraftMask;
    }

    public void setDerivedCraftMask(int mask) {
        this.derivedCraftMask = mask;
    }

    /** 各属性的加成（下标 = {@link ItemStat#ordinal()}）；**不落库**、换图/重登时由重算器重建 */
    private final transient double[] statMod = new double[ItemStat.values().length];

    /** 加成是否需要重算（改过锻造等级/合成配方，或刚加载出来） */
    private transient boolean derivedDirty = true;

    /** 正在重算中（防重入：重算内部读的是"基准 + 已算出的加成"，不能再触发一次 ensure） */
    private transient boolean derivedComputing = false;

    /** 重算器（由 `ItemDerivedStats` 在启动时装配；测试里显式装配） */
    private static volatile DerivedStatsResolver derivedResolver;

    private static boolean warnedNoResolver = false;

    /** 重算器契约：只写 {@code statMod}，**不许碰基准字段**（那属于数据库）。 */
    public interface DerivedStatsResolver {
        void recompute(ItemInstance it);
    }

    /** 由 `ItemDerivedStats` 在 `@PostConstruct` 里装配（进程内单例）。 */
    public static void setDerivedResolver(DerivedStatsResolver r) {
        derivedResolver = r;
        warnedNoResolver = false;
    }

    /** 标记加成失效（改过锻造等级 / 合成配方 / 刚加载完）。 */
    public void markDerivedDirty() {
        derivedDirty = true;
    }

    /** 基准值（= DB 那一列）。**持久化走的还是原来的 getter**，这里只是给重算/聚合一个统一入口。 */
    public double baseOf(ItemStat stat) {
        return switch (stat) {
            case DAMAGE_MIN -> damageMin;
            case DAMAGE_MAX -> damageMax;
            case ATTACK_RATING -> attackRating;
            case CRITICAL -> critical;
            case DEFENCE -> defence;
            case ABSORB -> absorb;
            case BLOCK_RATING -> blockRating;
            case SPEED -> speed;
            case INCREASE_LIFE -> increaseLife;
            case INCREASE_MANA -> increaseMana;
            case INCREASE_STAMINA -> increaseStamina;
            case LIFE_REGEN -> lifeRegen;
            case MANA_REGEN -> manaRegen;
            case STAMINA_REGEN -> staminaRegen;
            case RES_BIONIC -> resBionic;
            case RES_FIRE -> resFire;
            case RES_ICE -> resIce;
            case RES_LIGHTING -> resLighting;
            case RES_POISON -> resPoison;
        };
    }

    /** 加成（锻造 + 合成）；读取前先按需重算。 */
    public double modifier(ItemStat stat) {
        ensureDerived();
        return statMod[stat.ordinal()];
    }

    /** **有效值** = 基准 + 加成 —— 需要"生效属性"的地方用这个。 */
    public double effective(ItemStat stat) {
        ensureDerived();
        return baseOf(stat) + statMod[stat.ordinal()];
    }

    /** 有效值的整数形态（与字段类型一致；非整数字段请用 {@link #effective}）。 */
    public int effectiveInt(ItemStat stat) {
        return (int) Math.round(effective(stat));
    }

    /** 加成写入（重算器与效果应用专用）。 */
    public void setModifier(ItemStat stat, double value) {
        statMod[stat.ordinal()] = value;
    }

    /**
     * 把"该属性的当前生效值"设为 {@code v} —— 增长的公式（如"防御 +5%"）读的是**当前生效值**，
     * 所以这里按"v − 基准"记增量（增量可累加，基准不动）。
     */
    public void setEffective(ItemStat stat, double v) {
        statMod[stat.ordinal()] = v - baseOf(stat);
    }

    /** 清空全部加成（重算的第一步；不触发 ensure，避免重入）。 */
    public void clearModifiers() {
        java.util.Arrays.fill(statMod, 0.0);
        derivedDirty = false;
    }

    private void ensureDerived() {
        if (!derivedDirty || derivedComputing) {
            return;
        }
        DerivedStatsResolver r = derivedResolver;
        if (r == null) {
            // 不静默（AGENTS #12）：没装配重算器时锻造/合成的加成一律为 0，属性会偏小 —— 必须喊出来
            if (!warnedNoResolver) {
                warnedNoResolver = true;
                org.slf4j.LoggerFactory.getLogger(ItemInstance.class).error(
                        "[ItemStat] 没装配派生属性重算器（ItemDerivedStats）—— 锻造/合成加成一律按 0 处理，"
                                + "属性会偏小。测试里请显式 new ItemDerivedStats(...) 并 setDerivedResolver(...)。");
            }
            derivedDirty = false;
            return;
        }
        derivedComputing = true;
        try {
            r.recompute(this);      // 内部用 baseOf/setModifier/effective，不会再进 ensureDerived
            derivedDirty = false;
        } finally {
            derivedComputing = false;
        }
    }

    /** DB 主键 uid；未 INSERT 前为 null */
    private Long id;

    // ---- 归属位置 ----
    private int characterId;
    private int location;
    private int slot;

    /** 堆叠数量（可堆叠物 >1；装备恒 1） */
    private int count = 1;

    // ---- 定义引用 ----
    private Integer itemListId;
    private Integer itemCode;
    /** 模板（来自 gamedb.itemlist），供占格/分类/需求校验；不参与持久化 */
    private transient ItemList template;

    // ---- 掷点结果（对应 userdb.item 列）----
    private int durability;
    private int durabilityMax;
    private int resBionic, resEarth, resFire, resIce, resLighting, resPoison, resWater, resWind;
    private int damageMin, damageMax;
    private int attackRating;
    private double absorb;
    private int defence;
    private int critical;
    private int shootingRange;
    private int attackSpeed;
    private double blockRating;
    private double speed;
    private double manaRegen, lifeRegen, staminaRegen;
    private double increaseLife, increaseMana, increaseStamina;

    // ---- 需求（掷点时已做职业修正）----
    private int reqLevel, reqStrength, reqSpirit, reqTalent, reqAgility, reqHealth;
    private int price;
    private int jobCodeMask;

    // ---- 职业特效增益 ----
    private double specAbsorb;
    private int specDefence;
    private double specSpeed;
    private double specBlockRating;
    private int specAttackSpeed;
    private int specCritical;
    private int specShootingRange;
    private double specMagicMastery;
    private int specResBionic, specResEarth, specResFire, specResIce, specResLighting, specResPoison, specResWater, specResWind;
    private int specLevMana;
    private int specLevLife;
    private int specLevAttackRating;
    private int specLevDamageMax;
    private int specLevResBionic, specLevResEarth, specLevResFire, specLevResIce, specLevResLighting, specLevResPoison, specLevResWater, specLevResWind;
    private double specPerManaRegen;
    private double specPerLifeRegen;
    private double specPerStaminaRegen;

    // ---- 锻造/合成状态 ----
    /**
     * 锻造等级（原版 `ItemAgingNum[0]`）。
     * ⚠ **合成与锻造共用这一格**：锻造时 = 强化等级；合成时原版写的是"材料槽位+1"
     * （`sinTrade.cpp:5010`）—— 但我们用 {@link #agingNum2} 记**配方 id**，故这里合成**不写**。
     * 判"是不是合成物"看 {@link #kindCode}，别只看这个数。
     */
    private int agingNum;
    /** 双职：① 锻造的奇数/偶数级状态位（原版 `ItemAgingNum[1]`）；② **我们的合成配方 id**（原版 `sMixUniqueID1` 那一格）。
     *  我们的库没有 `mix_unique_id` 列，故借用此列 —— 见 `docs/锻造与合成-我们的实现方案.md` §2.2。 */
    private int agingNum2;
    private int agingExp;
    private int agingExpMax;
    /** `userdb.item.craft_mask` = 原版 `ItemKindMask`：合成/锻造效果**位掩码**（合成时"效果类型值就是掩码位"，见 {@link MixEffect}）。 */
    private int craftMask;
    /** `userdb.item.kind_code` = 原版 `ItemKindCode`：1 = 合成物（`ITEM_KIND_CRAFT`），2 = 锻造物，… */
    private int kindCode;
    /** `userdb.item.aging_protect` = 原版 `ItemAgingProtect[0]`：合成/锻造的**校验和**（反外挂比对，`playsub.cpp:4140-4156`）。 */
    private int agingProtect;

    /** 软删标记（丢弃/扫地销毁后 true，DB 行带 delete_time） */
    private boolean deleted;

    // ------------------------------------------------------------------
    // 便捷查询（依赖模板）
    // ------------------------------------------------------------------

    /** 占格宽（格数，模板 width/22；模板缺失按 1） */
    public int gridW() {
        if (template == null || template.getWidth() == null) {
            return 1;
        }
        return Math.max(1, template.getWidth() / 22);
    }

    /** 占格高（格数，模板 height/22） */
    public int gridH() {
        if (template == null || template.getHeight() == null) {
            return 1;
        }
        return Math.max(1, template.getHeight() / 22);
    }

    /**
     * 是否可堆叠（消耗/材料/药水等 count 语义）；装备不可堆叠恒 1。
     * <p>
     * DB classitem 即原版 INVENTORY_POS 位值：2副手/4单/6双手/8甲/16靴/32手/
     * 192戒/256宝石/512项链/2048护腕/8192药水/16384时装。
     *
     * ⚠ **不能把"有槽位位值"等同于"装备、不可堆叠"**：药水（`ItemClass.POTION = 8192`）
     * 的位值是**快捷槽**不是装备槽，它恰恰是**必须能堆叠**的消耗品 ——
     * 文档 `pt-core-gameplay.md` §19："能堆叠的主要是药水、材料"，
     * 药水槽也是按堆叠设计的（不装臂环每槽 2 个、装臂环按 potionspace）。
     * 漏掉药水曾导致背包里的药水无法合并（用户 2026-09-13 实测）。
     */
    public boolean stackable() {
        if (template == null || template.getClassItem() == null) {
            return true;
        }
        return ItemClass.isStackable(template.getClassItem());
    }

    /** 物品名（模板）。 */
    public String name() {
        return template == null ? "?" : template.getName();
    }

    /** 判断该槽位是否允许放本物品（装备槽位校验，后续由 EquipService 细化）。 */
    public int slotIndex() {
        return slot;
    }
}
