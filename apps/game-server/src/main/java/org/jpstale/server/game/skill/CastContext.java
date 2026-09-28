package org.jpstale.server.game.skill;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.skill.combat.SkillCombat;

/**
 * 一次待结算施法的**上下文** —— {@code SkillCastService.hit} 在事件帧时点组装，效果实现只读。
 *
 * <p>携带三类东西：
 * <ul>
 *   <li>**本次施法的事实**：施法者（{@link Player}）与其场景实体、技能 id、1 基等级
 *       （{@link #point()} / {@link #idx()}）、事件帧回报的目标 id（单体技能用；
 *       **目标以此刻的现实为准**，起手记的 id 只用于校验，见 {@code SkillCastService.hit}）；</li>
 *   <li>**参数表只读门面**（{@link #table1d} / {@link #table2d}，委托 {@link SkillDataRegistry}）
 *       —— 效果实现不必各自注入注册表；</li>
 *   <li>**面板派生**：攻击力区间（{@link #attackPower()}）与含端点掷点（{@link #roll}）。</li>
 * </ul>
 *
 * <p>伤害落地（扣血/广播/仇恨/死亡/击退）**不在这里**：那是 {@code combat.SkillCombat} 的职责，
 * 效果实现把算好的 {@code DamageResult} 交给它。
 */
public final class CastContext {

    private final Player player;
    private final PlayerEntity self;
    private final int skillId;
    /** 技能等级（1 基，1..10）。 */
    private final int point;
    /** 事件帧回报的目标（单体技能用；AoE 忽略它）。 */
    private final long targetId;
    /**
     * **服务端在起手掷定的技能参数**（0 = 本技能没有）—— Multi Spark 的道数。
     * 原版激活时一次掷定随 SkillCode 带给结算（`SkillSub.cpp:2785-2792`）；
     * `S2C_SkillStart.spark_count` 广播的是同一个数 ⇒ 客户端视觉与服务端结算同源。
     */
    private final int sparkCount;
    private final SkillDataRegistry skillData;
    private final PlayerStatCalculator statCalculator;
    /**
     * **施法瞬间的朝向**（弧度，0 = +Z；`null` = 客户端未上报）。
     * 原版方向型 AoE（Glacial Spike 的 dm_SelectRangeBox）用客户端活体 `Angle.y` ——
     * 原地转身不发移动包，实体上的角度停在最后一次移动 ⇒ 方向型技能必须用它。
     */
    private final Float casterYaw;

    public CastContext(Player player, PlayerEntity self, int skillId, int point, long targetId,
                       SkillDataRegistry skillData, PlayerStatCalculator statCalculator) {
        this(player, self, skillId, point, targetId, skillData, statCalculator, 0, null);
    }

    public CastContext(Player player, PlayerEntity self, int skillId, int point, long targetId,
                       SkillDataRegistry skillData, PlayerStatCalculator statCalculator, int sparkCount) {
        this(player, self, skillId, point, targetId, skillData, statCalculator, sparkCount, null);
    }

    public CastContext(Player player, PlayerEntity self, int skillId, int point, long targetId,
                       SkillDataRegistry skillData, PlayerStatCalculator statCalculator, int sparkCount,
                       Float casterYaw) {
        this.player = player;
        this.self = self;
        this.skillId = skillId;
        this.point = point;
        this.targetId = targetId;
        this.sparkCount = sparkCount;
        this.casterYaw = casterYaw;
        this.skillData = skillData;
        this.statCalculator = statCalculator;
    }

    /** 施法瞬间朝向；`null` = 客户端未上报（方向型技能按各自策略显式处理，不静默替值）。 */
    public Float casterYaw() {
        return casterYaw;
    }

    public Player player() {
        return player;
    }

    public PlayerEntity self() {
        return self;
    }

    public int skillId() {
        return skillId;
    }

    /** 技能等级（1 基）。 */
    public int point() {
        return point;
    }

    /** 参数表下标 = 等级 − 1（0 基）。 */
    public int idx() {
        return point - 1;
    }

    public long targetId() {
        return targetId;
    }

    /** 起手掷定的技能参数（Multi Spark = 道数；0 = 本技能没有）。 */
    public int sparkCount() {
        return sparkCount;
    }

    /** 参数表 1 维取值（表名是源码里的真实表名，如 {@code Pike_Wind_Push_Lenght}）。 */
    public double[] table1d(String name) {
        return skillData.table1d(name);
    }

    /** 参数表 2 维取值（{@code [等级][列]}，如 {@code Pike_Wind_Damage}）。 */
    public double[][] table2d(String name) {
        return skillData.table2d(name);
    }

    /** 该表在不在（取值前判长度用；表名错 ≠ 返回 null，取值会抛）。 */
    public boolean hasTable(String name) {
        return skillData.hasTable(name);
    }

    /** 该表实际项数（源码把个别表写短了，见 {@code SkillDataRegistry.tableLength}）。 */
    public int tableLength(String name) {
        return skillData.tableLength(name);
    }

    /** 该表在源码里的出处（file:line），写日志/注释倒查用。 */
    public String tableSource(String name) {
        return skillData.tableSource(name);
    }

    /** 施法者的面板攻击力区间 {@code [min,max]}（与普攻同源，别拿武器原始伤害顶替）。 */
    public int[] attackPower() {
        return statCalculator.attackPower(player);
    }

    /** 武器射程（世界单位）—— 解析"目标可以是玩家"的技能（Healing）时做距离门，与单体选敌同一口径。 */
    public double shootingRange() {
        return statCalculator.shootingRange(player);
    }

    /**
     * **装备伤害之和**（裸值）：{@code [min,max]} = 已装备物品的 `sItemInfo.Damage` 之和。
     * 两个用途：`Power2 = 面板攻击力 − 这个值`（Healing，`Damage.cpp:253-254`）；
     * 以及 Divine/Chain Lightning 的伤害**就是**它的掷点（原版把武器裸伤传进 `dm_SendRangeDamage`）。
     */
    public int[] weaponDamage() {
        return statCalculator.weaponDamage(player);
    }

    /** {@code [min,max]} 含端点随机（原版 GetRandomPos）。 */
    public int roll(double min, double max) {
        return SkillCombat.randBetween(min, max);
    }
}
