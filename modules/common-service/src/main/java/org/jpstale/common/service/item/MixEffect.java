package org.jpstale.common.service.item;

/**
 * 合成效果位（= `gamedb.mixlist.typeatributte*` 列的取值）—— 与 EU `shared/item.h:1249-1270` 的
 * `MIXEFFECT_*`、以及 11 职业的 `SIN_ADD_*` **逐位相同**（三仓库一致，见 `docs/锻造与合成-源码分析.md` §3.14.4）。
 *
 * <p>
 * ⚠ **效果类型值本身就是掩码位**（EU `MixHandler.cpp:1330` 的 `eMixEffect |= (EMixEffect)iType`）⇒
 * 物品实例的 {@code craftMask} 直接按位或即可，不需要另建"位→编号"的映射表。
 *
 * <p>
 * **数值 vs 百分比**：配方里每槽还有第三个列 {@code peratributte}（我们库里叫这个名字，但它**不是**"百分比开关"）——
 * 语义照抄 EU `AddAttributeBonusToItem`（`MixHandler.cpp:1503-1529`）：
 * {@code 1 = 数值直加}、{@code 0 = 按当前值百分比}。见 {@link Kind}。
 */
@lombok.extern.slf4j.Slf4j
public final class MixEffect {

    private MixEffect() {
    }

    /** 加法类型（`mixlist.peratributte*` 列）。 */
    public static final class Kind {
        private Kind() {
        }

        /** 数值直加（`*value += bonus`）。 */
        public static final int FLAT = 1;
        /** 按**当前值**百分比（`*value += value * bonus / 100`）。 */
        public static final int PERCENT = 0;
    }

    // ---- 位值（三仓库一致）----
    public static final int FIRE = 0x00000001;
    public static final int ICE = 0x00000002;
    public static final int LIGHTNING = 0x00000004;
    public static final int POISON = 0x00000008;
    public static final int ORGANIC = 0x00000010;
    public static final int CRITICAL = 0x00000020;
    public static final int ATTACK_RATING = 0x00000040;
    public static final int DAMAGE_MIN = 0x00000080;
    public static final int DAMAGE_MAX = 0x00000100;
    public static final int ATTACK_SPEED = 0x00000200;
    public static final int ABSORB = 0x00000400;
    public static final int DEFENCE = 0x00000800;
    public static final int BLOCK = 0x00001000;
    public static final int MOVE_SPEED = 0x00002000;
    public static final int HP = 0x00004000;
    public static final int MP = 0x00008000;
    public static final int SP = 0x00010000;
    public static final int HP_REGEN = 0x00020000;
    public static final int MP_REGEN = 0x00040000;
    public static final int SP_REGEN = 0x00080000;
    /** 药水槽容量 ⚠ **我们的物品实例没有这个字段**（只有模板 `itemlist.potionspace`）：位照记进 `craftMask`，
     *  读取侧需要时用"配方 id（`aging_num2`）反查配方"拿到数值 —— 见 {@code MixService} 类注释。 */
    public static final int POTION_STORAGE = 0x00100000;

    // ---- 一张表：位 / 协议 key / 中文名（**一处**，别写成两个 switch —— 加位时必漏一个）----
    private static final int[] BITS = {
            FIRE, ICE, LIGHTNING, POISON, ORGANIC, CRITICAL, ATTACK_RATING, DAMAGE_MIN, DAMAGE_MAX,
            ATTACK_SPEED, ABSORB, DEFENCE, BLOCK, MOVE_SPEED, HP, MP, SP, HP_REGEN, MP_REGEN, SP_REGEN,
            POTION_STORAGE,
    };
    private static final String[] KEYS = {
            "fire", "ice", "lightning", "poison", "organic", "critical", "attack-rating", "damage-min",
            "damage-max", "attack-speed", "absorb", "defence", "block", "move-speed", "hp", "mp", "sp",
            "hp-regen", "mp-regen", "sp-regen", "potion-storage",
    };
    private static final String[] LABELS = {
            "火抗", "冰抗", "雷抗", "毒抗", "生体抗", "必杀", "命中", "最小伤害", "最大伤害", "攻击速度",
            "吸收", "防御", "格挡", "移速", "生命上限", "灵力上限", "耐力上限", "生命再生", "灵力再生",
            "耐力再生", "药水槽容量",
    };

    /**
     * 协议用的文案 key（`mixe.<key>`）—— 发给客户端的**只有 key，不发文案**
     * （改文案不该动协议，见 AGENTS #24 的同一类：别让文案成为协议信号）。
     * 文案在客户端 `src/locales/{zh,en}.json` 的 `mixe` 段。
     */
    public static String keyOf(int bit) {
        for (int i = 0; i < BITS.length; i++) {
            if (BITS[i] == bit) {
                return "mixe." + KEYS[i];
            }
        }
        return "mixe.unknown";
    }

    /** 日志/管理端用的中文名（不参与判定）。 */
    public static String nameOf(int bit) {
        for (int i = 0; i < BITS.length; i++) {
            if (BITS[i] == bit) {
                return LABELS[i];
            }
        }
        return "未知位(0x" + Integer.toHexString(bit) + ")";
    }

    // ==================================================================
    // 效果位 → 实例字段：唯一实现（2026-09-22 从 MixService 搬来并改静态）
    // 为什么要搬：合成效果现在有两处要用 —— ①真合成时写一次；②**每次从库里加载装备时**
    // 按配方 id 重算（DB 只存基准属性 + 配方 id，不存合成后的数值）。
    // 放在这里是因为它只依赖 位 的定义，两个调用方都能用（也不再让重算模块反向依赖 MixService）。
    // ==================================================================

    /** 兼容旧调用（测试用）：等价于 {@code applyEffect(it, slot, true)}。 */
    public static Applied apply(ItemInstance it, MixRecipe.Slot slot) {
        return apply(it, slot, true);
    }

    /**
     * 单槽效果 → 实例字段。**唯一的"位→字段"映射**，两种用途共用同一张 switch：
     * <ul>
     *   <li>{@code write=true} —— 真合成（{@code mix()}）与测试；</li>
     *   <li>{@code write=false} —— **干跑**（{@code preview()}）：只算 before/after，一个字段都不改。</li>
     * </ul>
     * 照 EU `SetMixStatus` 的 switch（`MixHandler.cpp:1294-1330`）；加法类型见 {@link MixEffect.Kind}。
     *
     * <p>⚠ 干跑**逐槽读当前值**，不累加 —— 前提是"同一条配方里没有两个相同的位"。
     * 已实测我们库里 **283 条配方无一重复位**（`SELECT mixuniqueid ... HAVING count(*) &lt;&gt; count(DISTINCT b)` 为 0）。
     * 若将来数据里出现重复位，这里必须改成"边算边累加"，否则预览会比实际少算一次。
     *
     * <p>⚠ 唯一落不下的是 {@link MixEffect#POTION_STORAGE} —— 我们的实例没有该字段（见类注释），
     * 只记录位、不静默假装生效（读取侧用配方反查数值）。
     */
    public static Applied apply(ItemInstance it, MixRecipe.Slot slot, boolean write) {
        boolean flat = slot.kind() == MixEffect.Kind.FLAT;
        double v = slot.value();
        double before = 0;
        double after = 0;
        boolean intField = true;      // 该位对应的字段是不是整数（客户端显示用：整数不补小数位）
        switch (slot.bit()) {
            // 元素抗性：EU 的 saElementalDef[0]=生物 [2]=火 [3]=冰 [4]=雷 [5]=毒 —— 与我们 res_* 的顺序一致
            case MixEffect.ORGANIC -> {
                before = it.effective(ItemStat.RES_BIONIC);
                after = add(it.effective(ItemStat.RES_BIONIC), v, flat);
                if (write) { it.setEffective(ItemStat.RES_BIONIC, (int) after); }
            }
            case MixEffect.FIRE -> {
                before = it.effective(ItemStat.RES_FIRE);
                after = add(it.effective(ItemStat.RES_FIRE), v, flat);
                if (write) { it.setEffective(ItemStat.RES_FIRE, (int) after); }
            }
            case MixEffect.ICE -> {
                before = it.effective(ItemStat.RES_ICE);
                after = add(it.effective(ItemStat.RES_ICE), v, flat);
                if (write) { it.setEffective(ItemStat.RES_ICE, (int) after); }
            }
            case MixEffect.LIGHTNING -> {
                before = it.effective(ItemStat.RES_LIGHTING);
                after = add(it.effective(ItemStat.RES_LIGHTING), v, flat);
                if (write) { it.setEffective(ItemStat.RES_LIGHTING, (int) after); }
            }
            case MixEffect.POISON -> {
                before = it.effective(ItemStat.RES_POISON);
                after = add(it.effective(ItemStat.RES_POISON), v, flat);
                if (write) { it.setEffective(ItemStat.RES_POISON, (int) after); }
            }
            case MixEffect.CRITICAL -> {
                before = it.effective(ItemStat.CRITICAL);
                after = add(it.effective(ItemStat.CRITICAL), v, flat);
                if (write) { it.setEffective(ItemStat.CRITICAL, (int) after); }
            }
            case MixEffect.ATTACK_RATING -> {
                before = it.effective(ItemStat.ATTACK_RATING);
                after = add(it.effective(ItemStat.ATTACK_RATING), v, flat);
                if (write) { it.setEffective(ItemStat.ATTACK_RATING, (int) after); }
            }
            case MixEffect.DAMAGE_MIN -> {
                before = it.effective(ItemStat.DAMAGE_MIN);
                after = add(it.effective(ItemStat.DAMAGE_MIN), v, flat);
                if (write) { it.setEffective(ItemStat.DAMAGE_MIN, (int) after); }
            }
            case MixEffect.DAMAGE_MAX -> {
                before = it.effective(ItemStat.DAMAGE_MAX);
                after = add(it.effective(ItemStat.DAMAGE_MAX), v, flat);
                if (write) { it.setEffective(ItemStat.DAMAGE_MAX, (int) after); }
            }
            case MixEffect.ATTACK_SPEED -> {
                before = it.getAttackSpeed();
                after = add(it.getAttackSpeed(), v, flat);
                if (write) { it.setAttackSpeed((int) after); }
            }
            case MixEffect.ABSORB -> {
                intField = false;
                before = it.effective(ItemStat.ABSORB);
                after = add(it.effective(ItemStat.ABSORB), v, flat);
                if (write) { it.setEffective(ItemStat.ABSORB, after); }
            }
            case MixEffect.DEFENCE -> {
                before = it.effective(ItemStat.DEFENCE);
                after = add(it.effective(ItemStat.DEFENCE), v, flat);
                if (write) { it.setEffective(ItemStat.DEFENCE, (int) after); }
            }
            case MixEffect.BLOCK -> {
                intField = false;
                before = it.effective(ItemStat.BLOCK_RATING);
                after = add(it.effective(ItemStat.BLOCK_RATING), v, flat);
                if (write) { it.setEffective(ItemStat.BLOCK_RATING, after); }
            }
            case MixEffect.MOVE_SPEED -> {
                intField = false;
                before = it.effective(ItemStat.SPEED);
                after = add(it.effective(ItemStat.SPEED), v, flat);
                if (write) { it.setEffective(ItemStat.SPEED, after); }
            }
            case MixEffect.HP -> {
                before = (int) it.effective(ItemStat.INCREASE_LIFE);
                after = add((int) it.effective(ItemStat.INCREASE_LIFE), v, flat);
                if (write) { it.setEffective(ItemStat.INCREASE_LIFE, (int) after); }
            }
            case MixEffect.MP -> {
                before = (int) it.effective(ItemStat.INCREASE_MANA);
                after = add((int) it.effective(ItemStat.INCREASE_MANA), v, flat);
                if (write) { it.setEffective(ItemStat.INCREASE_MANA, (int) after); }
            }
            case MixEffect.SP -> {
                before = (int) it.effective(ItemStat.INCREASE_STAMINA);
                after = add((int) it.effective(ItemStat.INCREASE_STAMINA), v, flat);
                if (write) { it.setEffective(ItemStat.INCREASE_STAMINA, (int) after); }
            }
            case MixEffect.HP_REGEN -> {
                intField = false;
                before = it.effective(ItemStat.LIFE_REGEN);
                after = add(it.effective(ItemStat.LIFE_REGEN), v, flat);
                if (write) { it.setEffective(ItemStat.LIFE_REGEN, after); }
            }
            case MixEffect.MP_REGEN -> {
                intField = false;
                before = it.effective(ItemStat.MANA_REGEN);
                after = add(it.effective(ItemStat.MANA_REGEN), v, flat);
                if (write) { it.setEffective(ItemStat.MANA_REGEN, after); }
            }
            case MixEffect.SP_REGEN -> {
                intField = false;
                before = it.effective(ItemStat.STAMINA_REGEN);
                after = add(it.effective(ItemStat.STAMINA_REGEN), v, flat);
                if (write) { it.setEffective(ItemStat.STAMINA_REGEN, after); }
            }
            case MixEffect.POTION_STORAGE -> {
                intField = false;
                if (write) {
                    log.warn("[Mix] 配方效果「药水槽容量 +{}」暂无法落到物品实例（实例表没有该列）——"
                            + "位已记进 craft_mask，读取侧可用配方 id（aging_num2）反查数值", v);
                }
            }
            default -> {
                intField = false;
                if (write) {
                    log.warn("[Mix] 未知效果位 0x{}（值 {}，{}）——已记进 craft_mask，未改任何字段",
                            Integer.toHexString(slot.bit()), v, flat ? "数值" : "百分比");
                }
            }
        }
        return new Applied(slot.bit(), MixEffect.keyOf(slot.bit()), v, flat, before, after, intField);
    }
    /** 效果应用的结果（预览与日志用）：位 / 文案 key / 配方值 / 直加或百分比 / 改前 / 改后 / 是否整数字段 */
    public record Applied(int bit, String key, double value, boolean flat, double before, double after,
                          boolean intField) {
    }

    /** 整数加法器（照 EU：直加 / 按当前值百分比，百分比截断）。 */
    static int add(int cur, double v, boolean flat) {
        return flat ? cur + (int) v : cur + (int) ((cur * v) / 100.0);
    }

    /** 小数加法器。 */
    static double add(double cur, double v, boolean flat) {
        return flat ? cur + v : cur + (cur * v) / 100.0;
    }
}
