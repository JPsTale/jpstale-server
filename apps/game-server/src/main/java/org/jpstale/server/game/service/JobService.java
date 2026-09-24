package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.server.game.network.PlayerSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 转职推进（**任务驱动的接口**；用户 2026-09-24 裁定）。
 *
 * <p><b>架构</b>：转职走任务流程 —— 任务系统完成对应任务后把角色 props 里的任务位置 1
 * （{@code setPropInt("quest.level_20", 1)}），本模块据它给 rank+1。本类就是那个
 * <b>转职接口</b>（{@link #advance}），供后续任务系统在任务完成的处理链里调用；
 * 没有任务系统就没有转职 —— 这是显式的"还不能转"，不是缺功能。
 *
 * <p><b>与源码的关系（我方决定，与源码不同）</b>：原版 A 根（NewSourcePT-2023）是
 * <b>按等级自动转</b>（{@code sinQuest.cpp:293-306 sinCheckChageJob}：Level ≥ 20/40/60 ⇒ 1/2/3），
 * "任务 + 技能树全满 + 交任务物品"的流程在 A 根里整段被注释（{@code :222-290}）——
 * 那是私服没有任务系统的表现，<b>不代表原版设计不需要任务</b>（8 职业时代 B 根就只有任务式）。
 * 我们按任务式实现：等级门槛仍保留（{@link #questKeyFor} 里的 20/40/60 对应关系不变），
 * 但触发条件是任务完成，不是等级到达。原版的"配 {@code ChangeJobFace()} 换头"由我们
 * 的外观广播承担（rank 变 ⇒ 头模档位变，{@code playsub.cpp:1102-1143}）。
 *
 * <p>推进时做三件事：
 * <ol>
 *   <li>{@code rank += 1}（原版 {@code smCharInfo.ChangeJob}，JOB_CODE 不变 —— §2.3a）；</li>
 *   <li><b>放开洗点守卫</b>（{@code setSkillResetUsed(false)}）：原版转职把 {@code wVersion[1]} 清零
 *       （{@code sinMessageBox.cpp:1964/2267}），即"转职后可洗一次"；</li>
 *   <li>落库 + 外观广播 + 面板状态推送（rank 决定头模档位与面板职业名）。</li>
 * </ol>
 */
@Slf4j
@Service
public class JobService {

    /** rank 上限 = 4 转（原版 ChangeJob 0..3；5 转预留 4，本期不推进）。 */
    static final int MAX_RANK = 3;

    /**
     * GM 命令可设的 rank 上限 = 4（5 转预留档，客户端头模有 `d` 档）——比玩法上限（{@link #MAX_RANK}）宽：
     * GM 命令是测试/运营工具，允许把角色摆到玩法还没内容的位置去看表现。
     */
    static final int GM_MAX_RANK = 4;

    /** 推进结果：OK = 已推进；其余为拒绝原因（调用方记日志，**不许静默忽略**）。 */
    public enum Reason {
        /** 下一档的转职任务尚未完成（props 里没有该任务位，或仍是 0）。 */
        QUEST_NOT_DONE,
        /** 已到 4 转上限。 */
        MAX_RANK,
        /** GM 设值越界（合法范围 0..{@link #GM_MAX_RANK}）。 */
        BAD_RANK,
        /** 玩家为空（防御；不该发生）。 */
        NO_PLAYER,
        /** 推进成功。 */
        OK,
    }

    @Autowired
    private PlayerService playerService;

    @Autowired
    private AppearanceService appearanceService;

    /**
     * 下一档 rank 对应的转职任务位键（rank 0→`quest.level_20`、1→`quest.level_40`、2→`quest.level_60`）。
     *
     * <p>⚠ 名字里的数字沿用原版的等级门槛（20/40/60），但语义是<b>任务 id</b>——
     * 任务系统按它写完成标识；本模块不校验等级（等级门槛由任务自身的接取条件承担）。
     */
    public static String questKeyFor(int currentRank) {
        return switch (currentRank) {
            case 0 -> PlayerKey.QUEST_LEVEL_20.getKey();
            case 1 -> PlayerKey.QUEST_LEVEL_40.getKey();
            case 2 -> PlayerKey.QUEST_LEVEL_60.getKey();
            default -> throw new IllegalArgumentException("rank " + currentRank + " 没有下一档（上限 " + MAX_RANK + "）");
        };
    }

    /**
     * 转职接口（任务系统调用）：检查下一档任务位 == 1 ⇒ 推进一档。
     *
     * <p>拒绝时<b>什么都不做</b>（不落库、不广播、不清洗点守卫），只返回原因。
     * 每次调用最多推进<b>一档</b>——连升（如任务系统一次发两个任务位）由任务系统按序调两次，
     * 语义与原版"每次转职一个阶级"一致。
     *
     * @return OK = 已推进（rank/守卫/落库/广播都已发生）；否则为拒绝原因
     */
    public Reason advance(Player p) {
        Reason r = tryPromote(p);
        if (r != Reason.OK) {
            return r;
        }
        playerService.persistStats(p);            // persistStats 写 rank（转职推进是它的一个写者）
        appearanceService.recalcAndBroadcast(p);  // 自机 + 视野内广播；头模档位随 rank 变
        // 面板链：S2C_CharacterStatus.rank 随状态推送刷新职业名（外观链只管头模，不管面板）
        PlayerSession session = playerService.sessionOf(p);
        if (session != null) {
            playerService.sendPlayerStatus(session, p);
        }
        return Reason.OK;
    }

    /**
     * GM 直接设值（`/@set_rank <n>`，任务系统上线前的转职入口；用户 2026-09-24 裁定）。
     *
     * <p>与 {@link #advance} 的区别：**直接设值**（可升可降，绕过任务位），合法范围
     * 0..{@link #GM_MAX_RANK}；其余副作用（放开洗点守卫、落库、外观广播、面板推送）与转职一致——
     * GM 设 rank 本质是"替玩家摆到该转职状态"，表现必须与真实转职相同。
     * 值不变时也走完全流程（外观广播由 equals 比较自然拦住，不会重播动画）。
     *
     * @return OK = 已设置；BAD_RANK / NO_PLAYER = 拒绝（什么都没发生）
     */
    public Reason setRank(Player p, int rank) {
        if (p == null) {
            return Reason.NO_PLAYER;
        }
        if (rank < 0 || rank > GM_MAX_RANK) {
            return Reason.BAD_RANK;
        }
        int before = p.getRank();
        p.setRank(rank);
        p.setSkillResetUsed(false);   // 与转职同：设完即可洗点（原版转职清 wVersion[1]）
        playerService.persistStats(p);
        appearanceService.recalcAndBroadcast(p);
        PlayerSession session = playerService.sessionOf(p);
        if (session != null) {
            playerService.sendPlayerStatus(session, p);
        }
        log.info("[Job] [GM] {} rank {} -> {}（/@set_rank）", p.getName(), before, rank);
        return Reason.OK;
    }

    /**
     * 判定 + 纯内存推进（rank/守卫）；副作用（落库/广播/推送）留在 {@link #advance}。
     * 拆出来让"任务位门、一次一档、拒绝什么都不做"可以不带持久化依赖地测。
     */
    Reason tryPromote(Player p) {
        if (p == null) {
            return Reason.NO_PLAYER;
        }
        int current = p.getRank();
        if (current >= MAX_RANK) {
            return Reason.MAX_RANK;
        }
        String questKey = questKeyFor(current);
        if (p.getPropInt(questKey) != 1) {
            return Reason.QUEST_NOT_DONE;
        }
        promote(p, current + 1);
        return Reason.OK;
    }

    /**
     * 推进的**纯内存语义**：rank 置档 + 放开洗点守卫（原版转职清 {@code wVersion[1]}）。
     * 拆出来是为了让"推进了什么"可以不带持久化/广播依赖地测。
     */
    void promote(Player p, int target) {
        int before = p.getRank();
        p.setRank(target);
        p.setSkillResetUsed(false);
        log.info("[Job] {} 转职 rank {} -> {}（转职任务位 {} 已完成）",
                p.getName(), before, target, questKeyFor(before));
    }
}
