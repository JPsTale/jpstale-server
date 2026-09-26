package org.jpstale.server.game.skill.combat;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能的**选敌**（目标从哪来）—— 效果实现共用，只此一份（AGENTS #15）。
 *
 * <p>与"结算落地"（{@link SkillCombat}）分开：这里只回答"打谁"，不碰任何状态。
 */
@Slf4j
@Service
public class TargetSelectors {

    @Autowired
    private EntityRegistry entityRegistry;

    @Autowired
    private PlayerStatCalculator statCalculator;

    /**
     * 选敌：以己为中心的圆，同图、存活、非召唤物（必中族 AoE 用）。
     *
     * <p>逐字 {@code SkillSub.cpp:105-129}（Pike Wind 一族）。
     */
    public List<Monster> circleAround(PlayerEntity self, float radius) {
        List<Monster> targets = new ArrayList<>();
        for (Monster m : entityRegistry.allMonsters()) {
            if (!m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
                continue;
            }
            double dx = m.getX() - self.getX();
            double dz = m.getZ() - self.getZ();
            if (dx * dx + dz * dz <= (double) radius * radius) {
                targets.add(m);
            }
        }
        return targets;
    }

    /**
     * 单目标校验：存在/存活/非召唤物/同图/距离（≤ 武器射程）。
     * 不可用 ⇒ `null`（有日志，**不静默换目标** —— 调用方按"零目标"处理）。
     */
    public Monster single(Player player, PlayerEntity self, long targetId) {
        Monster m = entityRegistry.findMonster(targetId);
        if (m == null || !m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
            log.info("[Skill] {} 目标 {} 不可用（不存在/已死/召唤物/异图）", player.getName(), targetId);
            return null;
        }
        double dx = self.getX() - m.getX();
        double dz = self.getZ() - m.getZ();
        double range = statCalculator.shootingRange(player);
        if (dx * dx + dz * dz > range * range) {
            log.info("[Skill] {} 目标 {} 超距（>{}）", player.getName(), targetId, range);
            return null;
        }
        return m;
    }
}
