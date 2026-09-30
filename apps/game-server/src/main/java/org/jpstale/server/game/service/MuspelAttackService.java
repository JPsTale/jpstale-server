package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.skill.SkillBuffStates;
import org.jpstale.server.game.skill.combat.SkillCombat;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **Summon Muspel 的天使攻击**（祭司 T4.4）—— 服务端权威的宠物攻击 tick。
 *
 * <p>原版：天使是**客户端特效实体**（`HoEffectType_MusPel`，`HoEffectManager.cpp:779-1140`），
 * IDLE 每 `Summon_Muspell_Attack_Delay[档]` 秒找最近怪 → 冲刺 → 打击帧 `SendMuspellDamage`
 * （客户端 `dm_SendTransDamage` 发包，服务端 `Svr_Damge.cpp:3711` 把伤害**整体换成**
 * `GetRandomPos(Summon_Muspell_Damage[Param][0..1])` 后走普攻结算）+ Dancing Sword 斩击音。
 * 我们把"攻击节奏 + 目标选择 + 伤害"收进服务端（改包绕不过），客户端只演：
 * 结算随 `S2C_AttackResult.skill_id = SUMMON_MUSPELL` 下发 ⇒ 施法者客户端播天使冲刺/打击。
 *
 * <p>结算细节（逐字）：伤害 = 表掷（**不吃面板**，`:3723`）、禁暴击（`:3724` Critical[0]=0）、
 * 普攻命中/防御照走。目标 = 最近怪（原版 `FindNearMonster`）；施法者死亡/进村 ⇒ 天使消失
 * （`:843-846` 的终止条件），本 tick 自然无目标 ⇒ 不打。
 */
@Slf4j
@Service
public class MuspelAttackService {

    private final SessionManager sessionManager;
    private final PlayerService playerService;
    private final SkillBuffStates skillBuffStates;
    private final SkillDataRegistry skillData;
    private final DamageCalculator damageCalculator;
    private final org.jpstale.server.game.skill.combat.SkillCombat combat;
    private final org.jpstale.server.game.entity.EntityRegistry entityRegistry;

    /** 每玩家下一次允许攻击的时刻（节奏 = `Summon_Muspell_Attack_Delay[档]` 秒）。 */
    private final Map<Long, Long> nextAttackAt = new ConcurrentHashMap<>();

    /** 攻击射程：原版 `FindNearMonster` 找视野内最近怪；我们用 AOI 视距的一半（约 25 单位）做"天使会飞去打"的范围。 */
    private static final double ATTACK_RANGE = 25.0;

    public MuspelAttackService(SessionManager sessionManager, PlayerService playerService,
                               SkillBuffStates skillBuffStates, SkillDataRegistry skillData,
                               DamageCalculator damageCalculator,
                               org.jpstale.server.game.skill.combat.SkillCombat combat,
                               org.jpstale.server.game.entity.EntityRegistry entityRegistry) {
        this.sessionManager = sessionManager;
        this.playerService = playerService;
        this.skillBuffStates = skillBuffStates;
        this.skillData = skillData;
        this.damageCalculator = damageCalculator;
        this.combat = combat;
        this.entityRegistry = entityRegistry;
    }

    /** 每秒 tick（与再生/到期回扫同一节拍）。 */
    @Scheduled(fixedRate = 1000)
    public void tick() {
        long now = System.currentTimeMillis();
        for (PlayerSession s : sessionManager.getAllSessions()) {
            if (s == null || !s.isPlaying() || s.getCharacterId() == null) {
                continue;
            }
            Player p = playerService.getPlayer(s);
            if (p == null || p.getHp() <= 0) {
                continue;
            }
            int point = skillBuffStates.activeParam(p.getId(), SkillIds.SUMMON_MUSPELL.id());
            if (point < 1) {
                continue;
            }
            if (now < nextAttackAt.getOrDefault(p.getId(), 0L)) {
                continue;
            }
            PlayerEntity self = s.getEntity();
            if (self == null) {
                continue;
            }
            Monster target = nearestMonster(self);
            if (target == null) {
                continue;   // 视野内没有怪 ⇒ 天使待机（不打也不占节奏）
            }
            // 伤害 = `GetRandomPos(Summon_Muspell_Damage[Param][0..1])`（**不吃面板**，`:3723`）
            double[][] dmg = skillData.table2d("Summon_Muspell_Damage");
            int idx = Math.min(Math.max(point, 1), dmg.length) - 1;
            int petDamage = SkillCombat.randBetween(dmg[idx][0], dmg[idx][1]);
            DamageResult r = damageCalculator.calculatePlayerToMonster(p, target.combatStats(), petDamage,
                    DamageCalculator.SkillMods.NONE);
            combat.applyDamage(p, self, target, r, 0, SkillIds.SUMMON_MUSPELL.id());
            // 斩击音二选一随 AttackResult 走（客户端播）—— 此处只定下一次节奏
            int delay = attackDelaySeconds(point);
            nextAttackAt.put(p.getId(), now + delay * 1000L);
            log.debug("[Muspel] {} 的天使攻击 {}#{}（pet 伤 {} ⇒ {}）下次 {}s 后",
                    p.getName(), target.getName(), target.getId(), petDamage, r.getFinalDamage(), delay);
        }
    }

    private int attackDelaySeconds(int point) {
        double[] t = skillData.table1d("Summon_Muspell_Attack_Delay");
        int idx = Math.min(Math.max(point, 1), t.length) - 1;
        return (int) t[idx];
    }

    /** 最近的存活怪（同图、非召唤物、范围内）—— 原版 `FindNearMonster` 的服务端等价。 */
    private Monster nearestMonster(PlayerEntity self) {
        Monster best = null;
        double bestDist = ATTACK_RANGE * ATTACK_RANGE;
        for (Monster m : entityRegistry.allMonsters()) {
            if (!m.isAlive() || m.isSummon() || m.getMapId() != self.getMapId()) {
                continue;
            }
            double dx = m.getX() - self.getX();
            double dz = m.getZ() - self.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= bestDist) {
                bestDist = d2;
                best = m;
            }
        }
        return best;
    }
}
