package org.jpstale.server.game.model;

import lombok.Data;
import org.jpstale.server.game.network.PlayerSession;

/**
 * AI 上下文
 * 存储 AI 决策所需的数据
 */
@Data
public class AiContext {

    private PlayerSession targetPlayer; // 当前目标玩家
    private float targetX;
    private float targetY;
    private float targetZ;
    private float patrolX; // 巡逻目标点
    private float patrolZ;
    private long lastDecisionTime;
    private int decisionInterval = 500; // 决策间隔（毫秒）

    public boolean shouldDecide() {
        return System.currentTimeMillis() - lastDecisionTime >= decisionInterval;
    }

    public void updateDecisionTime() {
        lastDecisionTime = System.currentTimeMillis();
    }
}
