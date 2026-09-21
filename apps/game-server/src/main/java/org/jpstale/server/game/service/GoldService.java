package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.S2C_GoldChange;
import org.jpstale.server.proto.base.ServerMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * **金币的唯一入账/出账入口**（拾取金币、买卖、任务奖励、死亡代价都走它）。
 *
 * 为什么要有这一层：原版的金币保护是**散的** —— `sinPlusMoney`（`sinSubMain.cpp:2249`）自己
 * 从不判上限、永远返回 TRUE，保护全靠调用方；于是拾取路径判了、**商店卖出另用一份 `>=` 判定**
 * （还把 `MAX_MONEY` 重定义成 5 亿，`sinShop.cpp:8`）、快递金币那条完全不判。
 * 我们照抄**行为**、但不照抄这个结构：上限只在这里判一次。
 *
 * 上限公式照抄原版 `cINVENTORY::CheckMoneyLimit`（`sinInvenTory.cpp:6473-6506`）：
 * <pre>
 *   Level &lt;= 10                     → 200,000
 *   Level &gt;  10 且 ChangeJob == 0    → Level*200000 - 1800000
 *   ChangeJob == 1 / 2 / 3          → 10,000,000 / 50,000,000 / MAX_MONEY
 * </pre>
 * 判定是"**总持有 + 本次入账 &lt;= 上限**"，**相等允许**（原版用 `&lt;=`）；超限**不截断、不入账**。
 * `MAX_MONEY` 取 11 职业版的 `#define MAX_MONEY 1000000000`。
 *
 * ⚠ **已知差异**：我们的角色模型**没有"转职阶数"这一列**（`userdb.character` 有 `jobcode`，
 * `Player.rank`（0-7）是外观用的转职阶级），所以 `changeJob` 目前恒为 0 ⇒ **只有 `CJ=0` 那一支
 * 可达**（高转职档位 1000 万/5000 万/10 亿暂时够不到）。要接上需要先给角色补一列，等用户定。
 */
@Slf4j
@Service
public class GoldService {

    /** 原版 `#define MAX_MONEY 1000000000`（11 职业版 `sinInvenTory.cpp:17`） */
    public static final long MAX_MONEY = 1_000_000_000L;

    /** 未转职分支的常数（原版 `Level * 200000 - 1800000`） */
    private static final long PER_LEVEL = 200_000L;
    private static final long BASE_OFFSET = 1_800_000L;

    /** 操作结果。`OVER_LIMIT` / `INSUFFICIENT` 都**不改变金币**（不截断、不部分入账）。 */
    public enum Result {
        OK,
        /** 入账后会超过等级上限（原版 `MESSAGE_OVER_MONEY`） */
        OVER_LIMIT,
        /** 余额不足（花钱时） */
        INSUFFICIENT
    }

    private final PlayerService playerService;

    @Autowired
    public GoldService(PlayerService playerService) {
        this.playerService = playerService;
    }

    /** 等级（+转职阶数）对应的**总持有上限**；见类注释里的原版出处与我们的已知差异。 */
    public static long goldLimit(int level, int changeJob) {
        if (level <= 10) {
            return 200_000L;
        }
        switch (changeJob) {
            case 1: return 10_000_000L;
            case 2: return 50_000_000L;
            case 3: return MAX_MONEY;
            default: return Math.max(0L, level * PER_LEVEL - BASE_OFFSET);
        }
    }

    /** 当前角色（含尚未落库的增量）的上限。 */
    public static long goldLimitOf(Player p) {
        return goldLimit(p.getLevel(), 0);   // changeJob 恒 0：我们没有该列（见类注释）
    }

    /**
     * 加/扣金币。**校验在入口内完成**（不学原版把保护留给调用方）：
     * - `amount > 0`：`当前 + amount <= 上限`，否则 `OVER_LIMIT`（不截断）；
     * - `amount < 0`：余额够，否则 `INSUFFICIENT`。
     *
     * 成功时：改内存 + 落库 + 推 `S2C_GoldChange` + 刷新 HUD（`sendPlayerStatus` 带金币）。
     * 客户端的**金币音**由 `S2C_GoldChange` 触发（"钱到手"那一刻），花钱不出声。
     */
    public Result add(PlayerSession session, Player p, long amount, String reason) {
        if (amount == 0) {
            return Result.OK;
        }
        long cur = p.getGold();
        long next = cur + amount;
        if (amount > 0) {
            long limit = goldLimitOf(p);
            if (next > limit) {
                log.info("[Gold] {} 入账被拒（超等级上限）: {} + {} = {} > {}（{}）",
                    p.getName(), cur, amount, next, limit, reason);
                return Result.OVER_LIMIT;
            }
        } else if (next < 0) {
            log.info("[Gold] {} 扣款被拒（余额不足）: {} + {} < 0（{}）", p.getName(), cur, amount, reason);
            return Result.INSUFFICIENT;
        }
        p.setGold((int) next);
        playerService.persistStats(p);
        // 无会话时只落库不推送（沿用 `PlayerService.sendPlayerStatus` 的既有约定：
        // 离线发放/GM 后台操作是真实情况，不是"降级"）
        if (session != null && session.isLoggedIn()) {
            playerService.sendPlayerStatus(session, p);
            session.send(ServerMessage.newBuilder()
                .setGoldChange(S2C_GoldChange.newBuilder()
                    .setOldGold(cur).setNewGold(next).setReason(reason == null ? "" : reason).build())
                .build());
        }
        log.info("[Gold] {} {} → {}（{}{}）", p.getName(), cur, next, amount > 0 ? "+" : "", amount);
        return Result.OK;
    }
}
