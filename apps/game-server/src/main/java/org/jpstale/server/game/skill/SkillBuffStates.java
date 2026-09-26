package org.jpstale.server.game.skill;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家身上**技能型限时增益/减益**的运行态（内存，不落库）。
 *
 * <p>原版同类状态是 rsPLAYINFO 上的成对字段（`dwSkill_HolyReflection_Time` + `_Param`，`OnSever.cpp:34130`），
 * 登录清零（`:12757-12758`）—— 我们用 {@code 玩家 id → 技能 id → {到期时刻, 参数值}} 一张表表达同一件事，
 * 查询时判过期（不靠每 tick 扫），同样**重登不延续**（离线清理见 {@link #clear}）。
 *
 * <p>与物品 buff（`BuffStateService`，带图标条推送）**分开**：技能增益目前没有图标条链路，
 * 不共用那套推送；Holy Reflection 先用，Virtual Life / Divine Force 等限时增益后续走这里。
 */
@Service
public class SkillBuffStates {

    /** playerId → (skillId → {untilMs, param})。 */
    private final Map<Long, Map<Integer, long[]>> buffs = new ConcurrentHashMap<>();

    /** 施加/刷新（同一技能重复施放 = 覆盖，原版同此：直接重写 Time/Param 两字段）。 */
    public void apply(long playerId, int skillId, long durationMs, int param) {
        buffs.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(skillId, new long[]{System.currentTimeMillis() + durationMs, param});
    }

    /**
     * 生效中的参数值；未施放/已过期 ⇒ 0（过期顺手清字段，避免残留）。
     * 查询即判过期，调用方不用再比时间。
     */
    public int activeParam(long playerId, int skillId) {
        Map<Integer, long[]> bySkill = buffs.get(playerId);
        if (bySkill == null) {
            return 0;
        }
        long[] entry = bySkill.get(skillId);
        if (entry == null) {
            return 0;
        }
        if (System.currentTimeMillis() >= entry[0]) {
            bySkill.remove(skillId);
            return 0;
        }
        return (int) entry[1];
    }

    /** 玩家离线：清掉他的全部技能增益（原版登录清零语义的等价物）。 */
    public void clear(long playerId) {
        buffs.remove(playerId);
    }
}
