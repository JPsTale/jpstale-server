package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Player;
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
 * 耐力另含原版天生回复 3.8 + Level/7（sinSetRegen InCreaSTM）。小数部分用累加器累积，
 * 满 1 点才落地，星遗石 0.1/s 这样的低值也能平稳生效。体力全日制回复（原版 Recovery_Stamina）。
 * <p>
 * 由 GameServer.tick 驱动（固定 tick 循环单线程），玩家死亡时不回复，满值不推送。
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

    /** 结算周期：1000ms */
    private static final long CYCLE_MS = 1000;

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
            if (p == null || p.getHp() <= 0) {
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
        double stmRegen = statCalculator.stmRegenTotal(p);

        double[] acc = accumulators.computeIfAbsent(p.getId(), k -> new double[3]);
        acc[0] += hpRegen;
        acc[1] += mpRegen;
        acc[2] += stmRegen;

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
        int ds = (int) acc[2];
        if (ds > 0) {
            acc[2] -= ds;
            if (p.getSp() < p.getMaxSp()) {
                p.setSp(Math.min(p.getMaxSp(), p.getSp() + ds));
                changed = true;
            }
        }
        return changed;
    }
}