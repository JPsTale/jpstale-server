package org.jpstale.server.game.skill.combat;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.AiEngine;
import org.jpstale.server.game.service.BattleLogService;
import org.jpstale.server.game.service.CombatService;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.proto.base.S2C_AttackResult;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 技能伤害的**结算落地**（扣血/战报/仇恨/击退/死亡/结果广播）—— 效果实现共用，只此一份。
 *
 * <p>各职业技能方法自己用 {@code DamageCalculator} 算出 {@link DamageResult}（模型各不相同：
 * 必中/暴击加成/命中加成/种族加成……），然后把"落地"交给本类 —— 落地动作与普攻同口径，
 * 只允许有一份（AGENTS #15）。
 */
@Slf4j
@Service
public class SkillCombat {

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private BattleLogService battleLogService;

    /** 死亡入口（同包包私有共用） */
    @Autowired
    private CombatService combatService;

    /** 受击反击（与普攻同口径）；@Lazy 防 AI 与战斗的循环依赖 */
    @Lazy
    @Autowired
    private AiEngine aiEngine;

    @Autowired
    private PlayerService playerService;

    /**
     * 结算一条伤害：扣血/死亡 + S2C_AttackResult 广播 + 仇恨（与普攻同口径）。
     *
     * @param knockbackDist 推离距离（世界单位）= 原版 `AttackSize`（= `Pike_Wind_Push_Lenght`）；
     *                      0 = 不推。
     */
    public void applyDamage(Player player, PlayerEntity self, Monster m, DamageResult r, float knockbackDist) {
        applyDamage(player, self, m, r, knockbackDist, 0);
    }

    /**
     * 同上，但带 **skillId** —— `S2C_AttackResult.skill_id` 随包下发：客户端的技能视觉
     * （如 Divine Lightning 的逐目标落雷）按它反查"这条结算出自哪一招"（AGENTS #14 同步结果）。
     * 普攻/未知传 0。
     */
    public void applyDamage(Player player, PlayerEntity self, Monster m, DamageResult r, float knockbackDist, int skillId) {
        S2C_AttackResult.Builder ar = S2C_AttackResult.newBuilder()
                .setAttackerId(player.getId())
                .setTargetId(m.getId())
                .setDamage(r.getFinalDamage())
                .setIsCritical(r.isCritical())
                .setHitIndex(0)
                .setSkillId(skillId);
        if (r.isMissed()) {
            ar.setMissed(true);
            broadcastResult(self, ar);
            return;
        }
        ar.setMissed(false);
        m.setHp(m.getHp() - r.getFinalDamage());
        battleLogService.playerDealtDamage(playerService.sessionOf(player), m.getName(),
                r.getFinalDamage(), r.isCritical());

        // 受击反击（Evil 无目标时；Neutral 受击也反击）——与普攻同口径
        if (m.getNature() == 0 || m.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(m, self, self.getX(), self.getZ());
        }

        knockback(self, m, knockbackDist);

        if (!m.isAlive()) {
            combatService.handleMonsterDeath(m, player);
        }
        broadcastResult(self, ar);
    }

    /**
     * 推离（AttackState=1），逐字 `Svr_Damge.cpp:2143-2172`：
     *   ang2 = 怪→施法者 的角；ang = ang2+180°（转身背对玩家）；MoveAngle(dist)；再转回来。
     *   dist = AttackSize − 与施法者的水平距离 ⇒ **越近推得越远**；已在范围外（≤0）不推。
     *   两条门：|Δy|>100 或水平距>800 ⇒ 不推。
     * 方向 = **远离**施法者（`-dx/-dz` = 从施法者指向怪）。
     */
    private void knockback(PlayerEntity self, Monster m, float knockbackDist) {
        if (knockbackDist > 0 && m.isAlive()) {
            double dx = self.getX() - m.getX();
            double dy = self.getY() - m.getY();
            double dz = self.getZ() - m.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dy) <= 100 && horizontal <= 800) {
                double dist = knockbackDist - horizontal;
                if (dist > 0) {
                    m.moveTo(m.getX() - dx, m.getY(), m.getZ() - dz, dist);
                }
            }
        }
    }

    private void broadcastResult(PlayerEntity self, S2C_AttackResult.Builder ar) {
        messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder().setAttackResult(ar).build());
    }

    /** [min,max] 含端点随机（原版 GetRandomPos）。 */
    public static int randBetween(double min, double max) {
        int lo = (int) Math.round(min);
        int hi = (int) Math.round(max);
        return lo + ThreadLocalRandom.current().nextInt(Math.max(1, hi - lo + 1));
    }
}
