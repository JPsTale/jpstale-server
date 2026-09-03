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
    private double targetX;
    private double targetY;
    private double targetZ;
    private double patrolX; // 巡逻目标点
    private double patrolZ;
    private long lastDecisionTime;
    private int decisionInterval = 500; // 决策间隔（毫秒）

    public boolean shouldDecide() {
        return System.currentTimeMillis() - lastDecisionTime >= decisionInterval;
    }

    public void updateDecisionTime() {
        lastDecisionTime = System.currentTimeMillis();
    }
}
