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
     * 选敌：**身前矩形**（Glacial Spike 的 `dm_SelectRangeBox` 族，必中）。
     *
     * <p>逐字 {@code character.cpp:17017-17023}（`SKILL_PLAY_GLACIAL_SPIKE`，MotionEvent==1）：
     * {@code rect.left=-50; rect.right=50; rect.top=0; rect.bottom=340(+20+20)} —— 施法者坐标系里
     * 横向 ±50、前方 0..340 的矩形，无高度门。 FALSE = 不做命中判定（必中族）。
     */
    public List<Monster> boxInFront(PlayerEntity self, double halfWidth, double depth) {
        List<Monster> targets = new ArrayList<>();
        for (Monster m : entityRegistry.allMonsters()) {
            if (!m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
                continue;
            }
            if (inFrontBox(self.getX(), self.getZ(), self.getAngle(), m.getX(), m.getZ(), halfWidth, depth)) {
                targets.add(m);
            }
        }
        return targets;
    }

    /**
     * {@code boxInFront} 的**纯几何判定**（可单测）：目标是否落在施法者坐标系里
     * "横向 ±halfWidth、前方 0..depth" 的矩形内。
     *
     * <p>角度约定与 {@code moveToward} 同源：yaw 0 = +Z，前向 = (sinθ, cosθ)。
     */
    public static boolean inFrontBox(double selfX, double selfZ, double yaw,
                                     double targetX, double targetZ, double halfWidth, double depth) {
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double dx = targetX - selfX;
        double dz = targetZ - selfZ;
        double forward = dx * sin + dz * cos;    // 投到前向轴
        double lateral = dx * cos - dz * sin;    // 投到横向轴
        return forward >= 0 && forward <= depth && Math.abs(lateral) <= halfWidth;
    }

    /**
     * 同图的存活怪（非召唤物）——**不带距离条件**的基础候选集。
     *
     * <p>Divine Lightning（轮转扫描）/ Chain Lightning（最近邻链）在它之上做各自的走查：
     * 链式/轮转的距离与排序规则是**技能自己的知识**（规格书 §2.3 / §3.3），不进共用层。
     */
    public List<Monster> monstersOnMap(PlayerEntity self) {
        List<Monster> candidates = new ArrayList<>();
        for (Monster m : entityRegistry.allMonsters()) {
            if (!m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
                continue;
            }
            candidates.add(m);
        }
        return candidates;
    }

    /**
     * 按 id 取怪，**只做"存在且存活"** —— 召唤物、距离、同图等由**调用方按各自技能的源码规则**判，
     * 不在这里替它决定（例：Healing 按源码**允许**治召唤物、距离门是 `GetSkillDistRange` 的
     * `180 * fONE`，与"攻击类"的"非召唤物 + 武器射程"不是同一套）。
     */
    public Monster aliveMonster(long monsterId) {
        Monster m = entityRegistry.findMonster(monsterId);
        return (m != null && m.isAlive()) ? m : null;
    }

    /**
     * 单目标校验：存在/存活/非召唤物/同图/距离（≤ 武器射程）。
     *
     * <p>⚠ **这是"攻击类"技能的规则集**（Pike Wind / Critical Hit / Jumping Crash 一族），
     * 不是通用规则：治疗类另有依据（见 {@link #aliveMonster}）。
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
