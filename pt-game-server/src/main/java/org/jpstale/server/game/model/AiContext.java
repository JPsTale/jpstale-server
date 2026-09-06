package org.jpstale.server.game.model;

import lombok.Data;
import org.jpstale.server.game.entity.PlayerEntity;

/**
 * AI 上下文
 * 存储 AI 决策所需的数据(MovementService 读取 target/patrol 用于位移)
 */
@Data
public class AiContext {

    /** 当前目标玩家实体(怪索敌/攻击的目标;不依赖 PlayerSession 游戏字段, D11) */
    private PlayerEntity targetPlayer;
    private double targetX;
    private double targetY;
    private double targetZ;
    private double patrolX; // 巡逻目标点
    private double patrolZ;
}
