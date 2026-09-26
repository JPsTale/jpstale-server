package org.jpstale.server.game.skill;

import java.util.List;

/**
 * 一段事件帧的结算结果（`SkillCastService.hit` 的返回形状）。
 *
 * <p>`mpCost` 是这一招的 MP 消耗（起手时已扣过，这里只是**回报**给调用方/测试，
 * 不再扣一次）。命中明细 = {@link HitTarget} 列表（零目标 = 合法的"没打中任何东西"）。
 */
public record SegmentResult(int skillId, int hitIndex, int mpCost, List<HitTarget> hits) {
}
