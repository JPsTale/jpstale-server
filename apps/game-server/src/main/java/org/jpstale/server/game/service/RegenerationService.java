package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生命/魔法/耐力自动回复（原版 Life_Regen / Mana_Regen / Stamina_Regen 语义）。
 * <p>
 * 每 1 秒结算一次：回复量 = 装备再生属性固定值（精确 0.1，如戒指每秒 +7.3 HP），
 * 满 1 点才落地，星遗石 0.1/s 这样的低值也能平稳生效。体力全日制回复（原版 Recovery_Stamina）。
 * <p>
 * 由 GameServer.tick 驱动（固定 tick 循环单线程），玩家死亡时不回复，满值不推送。
 * <p>
 * ⚠ **不要在这里发 `S2C_Recovery`**（用户 2026-09-16 明确）：被动缓慢回复是"每秒一次"的
 * 持续过程，广播飘字会把它变成每秒一个 "+N" 刷屏。那条消息只给**一次性回复事件**
 * （药水 / 治疗技能 / 生命转换），入口在 `ItemNetworkHandler.broadcastRecovery`。
 * 这里只刷 HUD（`sendPlayerStatus`）。
 */
@Slf4j
@Component
public class RegenerationService {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private MovementService movementService;

    /** 技能型限时增益（Regeneration Field 的持续时间窗从这里查） */
    @Autowired
    private org.jpstale.server.game.skill.SkillBuffStates skillBuffStates;

    /** 全队名单（田野只覆盖**队友**与自己） */
    @Autowired
    private org.jpstale.server.game.service.PartyService partyService;

    /** 技能参数表（Regeneration_Field_* 从注册表读） */
    @Autowired
    private org.jpstale.common.service.skill.SkillDataRegistry skillData;

    /** 结算周期：1000ms */
    private static final long CYCLE_MS = 1000;

    /**
     * 本玩家当前从 Regeneration Field 得到的每秒再生加成 `{hp, mp}`。
     *
     * 出自共享层：`sinInvenTory.cpp:8971`（数值）+ `character.cpp:17033-17055`（队友范围门）+
     * `sinSkill.cpp:7504`（自/队友的 Flag 差）。注册表缺表 ⇒ 按没有处理（数值缺口显式，不编值）。
     */
    private double[] regenerationFieldBonus(Player p) {
        double[] out = {0, 0};
        double[] life = skillData.table1d("Regeneration_Field_LifeRegen");
        double[] mana = skillData.table1d("Regeneration_Field_ManaRegen");
        if (life == null || mana == null) {
            return out;
        }
        // 自己施放的：全额（Flag=1）
        int own = skillBuffStates.activeParam(p.getId(),
            org.jpstale.server.common.enums.skill.SkillIds.REGENERATION_FIELD.id());
        if (own >= 1 && own <= life.length && own <= mana.length) {
            out[0] += life[own - 1];
            out[1] += mana[own - 1];
        }
        // 队友施放的：在范围内才有，生命全额、魔法减半（Flag=2）
        org.jpstale.server.game.entity.PlayerEntity self = playerService.entityOf(p);
        if (self == null) {
            return out;
        }
        for (Player member : partyService.membersOf(p.getId())) {
            if (member.getId() == p.getId()) {
                continue;
            }
            int pt = skillBuffStates.activeParam(member.getId(),
                org.jpstale.server.common.enums.skill.SkillIds.REGENERATION_FIELD.id());
            if (pt < 1 || pt > life.length || pt > mana.length) {
                continue;
            }
            double[] areaTable = skillData.table1d("Regeneration_Field_Area");
            if (areaTable == null || pt > areaTable.length) {
                continue;
            }
            org.jpstale.server.game.entity.PlayerEntity caster = playerService.entityOf(member);
            if (caster == null || caster.getMapId() != self.getMapId()) {
                continue;
            }
            double dx = caster.getX() - self.getX();
            double dz = caster.getZ() - self.getZ();
            double range = areaTable[pt - 1];
            if (dx * dx + dz * dz > range * range) {
                continue;
            }
            if (Math.abs(caster.getY() - self.getY()) >= 16.0) {
                continue;
            }
            out[0] += life[pt - 1];
            out[1] += mana[pt - 1] / 2.0;
        }
        return out;
    }

    private long lastTick = 0;
    /** charId → {hpAcc, mpAcc, stmAcc} 小数累加器 */
    private final Map<Long, double[]> accumulators = new ConcurrentHashMap<>();

    public void tick(long nowMs) {
        if (lastTick == 0) {
            lastTick = nowMs;
            return;
        }
        if (nowMs - lastTick < CYCLE_MS) {
            return;
        }
        lastTick = nowMs;
        settle();
    }

    private void settle() {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session == null || !session.isPlaying() || session.getCharacterId() == null) {
                continue;
            }
            Player p = playerService.getPlayer(session);
            // 死亡（躺下等复活）期间不回血：enterDeath 已把 hp 置 0，这里是第二道闸 ——
            // 免得任何"把 hp 抬起来"的路径让尸体半死不活
            org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
            if (p == null || p.getHp() <= 0 || (ent != null && ent.isDead())) {
                continue;
            }
            boolean changed = settleOne(p);
            if (changed) {
                playerService.sendPlayerStatus(session, p);
            }
        }
    }

    private boolean settleOne(Player p) {
        double hpRegen = statCalculator.regenHp(p);
        double mpRegen = statCalculator.regenMp(p);
        double stmRegen = statCalculator.regenStm(p);

        // **Regeneration Field（祭司 T4.2，"上吊"）**：持续期内给再生加成 ——
        // `sinInvenTory.cpp:8971-8976`：`Life_Regen += LifeRegen[point-1]`（全额）、
        // `Mana_Regen += ManaRegen[point-1] / Flag`（自己 Flag=1 全额，**队友 Flag=2 减半**，
        // `sinSkill.cpp:7504-7533` 的 `Flag = 1 + Party`）。范围 = 施法者 XZ ≤ Area[point-1]
        // （比较平方）且高度差 <16（`character.cpp:17050-17052`）—— 队友侧逐 tick 判。
        double[] field = regenerationFieldBonus(p);
        hpRegen += field[0];
        mpRegen += field[1];

        double[] acc = accumulators.computeIfAbsent(p.getId(), k -> new double[3]);
        acc[0] += hpRegen;
        acc[1] += mpRegen;
        acc[2] += stmRegen;

        // 跑步耐力消耗（原版 sinUseStamina+sinSetRegen）：本结算窗口累计跑步毫秒
        // 折算成耐力扣减，与原版逐帧 DeCreaSTM/(70/4) 分块落地语义一致。
        long runMs = movementService.consumeRunMs(p.getId());
        if (runMs > 0) {
            acc[2] -= statCalculator.staminaUsePerSec(p) * runMs / 1000.0;
        }

        boolean changed = false;
        int dh = (int) acc[0];
        if (dh > 0) {
            acc[0] -= dh;
            if (p.getHp() < p.getMaxHp()) {
                p.setHp(Math.min(p.getMaxHp(), p.getHp() + dh));
                changed = true;
            }
        }
        int dm = (int) acc[1];
        if (dm > 0) {
            acc[1] -= dm;
            if (p.getMp() < p.getMaxMp()) {
                p.setMp(Math.min(p.getMaxMp(), p.getMp() + dm));
                changed = true;
            }
        }
        // 体力：回复与消耗在同一个累加器内净额结算（可为负）
        int ds = (int) acc[2];
        if (ds != 0) {
            acc[2] -= ds;
            if (ds > 0 && p.getSp() < p.getMaxSp()) {
                p.setSp(Math.min(p.getMaxSp(), p.getSp() + ds));
                changed = true;
            } else if (ds < 0 && p.getSp() > 0) {
                if (p.getSp() + ds <= 0) {
                    log.debug("Player {} {} 耐力耗尽，强制走路", p.getName(), p.getId());
                }
                p.setSp(Math.max(0, p.getSp() + ds));
                changed = true;
            }
        }
        return changed;
    }
}