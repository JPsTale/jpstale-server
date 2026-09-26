package org.jpstale.server.game.skill;

/**
 * 一个目标的结算明细（伤害/暴击/miss/击退），供日志与测试。
 *
 * <p>效果层（{@code job.*}）的返回词汇：{@code JobSkills.settle} 返回的就是它。
 * 伤害落地本身在 {@code combat.SkillCombat.applyDamage}，这里只是**结果记录**。
 */
public record HitTarget(long monsterId, int damage, boolean critical, boolean missed, boolean knockedBack) {
}
