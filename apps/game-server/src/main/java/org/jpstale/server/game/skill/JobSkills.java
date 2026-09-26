package org.jpstale.server.game.skill;

import java.util.List;

/**
 * 一个职业的技能效果集 —— **一职业一个实现**（2026-09-26 用户裁定：最多 11 个类、
 * 每类 20 个技能 = 5 转 × 4 槽）。
 *
 * <p>这个形状与 **EU 参考源码的组织一致**（{@code Fighter.cpp} / {@code Assassin.cpp} /
 * {@code Atalanta.cpp} / …，而不是 NewSourcePT 那个 128 case 的大文件），也与逐字取证的
 * 移植册（{@code docs/技能系统-<职业>.md}）一一对应：移植某职业第 n 招 = 打开对应源文件的
 * 那个 case，翻成实现类里的一个方法，出处写在方法注释上。
 *
 * <p>实现只回答"这一招结算出什么"：读参数表（{@link CastContext#table1d}）→ 选目标
 * （{@code combat.TargetSelectors}）→ 算伤害（{@code DamageCalculator}）→ 把结果交给
 * {@code combat.SkillCombat} 落地。**共用机制不进实现类**：选敌/落地/击退在 combat 包里
 * 只有一份，11 个实现类共享（AGENTS #15 —— 同一算法出现第二份就是 bug 的种子）。
 *
 * <p>生命周期（起手校验/扣 MP/CD/段防重/待结算登记）在 {@code SkillCastService}，
 * 实现类不碰；新迁一个技能 = 在实现类的注册表里加一行 + 写一个方法，别处零改动。
 */
public interface JobSkills {

    /** 职业号（1..11；skillId 高 16 位 = 它）。 */
    int job();

    /**
     * 结算一次事件帧。
     *
     * @return 命中明细；**`null` = 该技能本类未实现（= 未迁入）** —— 正常链路里
     *         {@code SkillCastService.begin} 已按 {@link #handles} 挡住并在起手前转旧路，
     *         走到这里还拿到 null 只可能是"绕过起手直接打事件帧"的改包路径，
     *         调用方按"零目标"处理并留日志（不静默）。
     */
    List<HitTarget> settle(CastContext c);

    /** 该技能是否已由本类实现（起手门用：没实现的 ⇒ {@code NOT_MIGRATED}，调用方走旧路）。 */
    boolean handles(int skillId);

    /**
     * 面板用：该技能该等级的"伤害加成百分比"区间（{@code {min,max}}；单一值时两者相等）。
     * `null` = 该技能**不是**"攻击力 ×(1+%)"模型（面板不显示伤害行，也不编一个数）。
     * 与 {@link #settle} 用同一张表 —— 面板与结算是同一份定义（AGENTS #15）。
     *
     * @param point 1 基技能等级
     */
    default int[] powerPct(int skillId, int point) {
        return null;
    }
}
