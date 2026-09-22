package org.jpstale.common.service.item;

/**
 * **会被锻造/合成改变的属性字段** —— 这份清单是"派生属性模型"的唯一定义。
 *
 * <p><b>为什么要有它</b>（2026-09-22 用户定的设计）：数据库里**只存基准值**（模板掷出来的那个数）
 * 加上"锻造等级 N"与"合成配方 id"；装备的最终属性在**读取时**算出来。
 * 这样改一次锻造/合成规则，**所有已存在的装备都会跟着变对** —— 尤其是锻造会失败掉级，
 * 存"算好的数值"就必须再写一套反向减法去抵消，而反向永远不如"拿 N 重算"精确
 * （原版 EU 正是"烘焙 + 反向"：`AgeHandler.cpp` 的 `OnDownAge` 用 `DownDamage/DownCritical/DownDefense`
 * 逐条抵消，还必须把**原等级**传进去判该减多少 —— 见 `docs/打造系统-实现与UI.md §10`）。
 *
 * <p><b>三件东西别混</b>：
 * <ul>
 *   <li><b>基准</b>（base）：实例字段本身，= 数据库里那一列。持久化读写它，**永远不改**。</li>
 *   <li><b>加成</b>（modifier）：锻造 + 合成给这份装备加了多少（{@code ItemInstance.statMod}）。**不落库**，
 *       每次按 N / 配方 id 重算（dirty 标记控制时机）。</li>
 *   <li><b>有效值</b>（effective）= 基准 + 加成 —— 只有**需要生效值的地方**才用它
 *       （角色属性聚合、下发给客户端显示）。⚠ 别去覆盖 `getDamageMin()` 之类的原 getter：
 *       持久化也是用那些方法取值的，一覆盖就会把"算好的数"写回库里（用户 2026-09-22 的原话提醒）。</li>
 * </ul>
 */
public enum ItemStat {
    DAMAGE_MIN(true),
    DAMAGE_MAX(true),
    ATTACK_RATING(true),
    CRITICAL(true),
    DEFENCE(true),
    ABSORB(false),
    BLOCK_RATING(false),
    SPEED(false),
    INCREASE_LIFE(false),
    INCREASE_MANA(false),
    INCREASE_STAMINA(false),
    LIFE_REGEN(false),
    MANA_REGEN(false),
    STAMINA_REGEN(false),
    RES_BIONIC(true),
    RES_FIRE(true),
    RES_ICE(true),
    RES_LIGHTING(true),
    RES_POISON(true);

    private final boolean intField;

    ItemStat(boolean intField) {
        this.intField = intField;
    }

    /** 该字段在实例里是不是整数型（读取有效值时四舍五入，与字段类型一致）。 */
    public boolean isInt() {
        return intField;
    }
}
