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
    /**
     * 当前目标**怪物**（召唤物 ↔ 怪 这条链）。
     *
     * <p>与 {@link #targetPlayer} 互斥：同一时刻只有一个非空。位移侧只读 {@code targetX/Y/Z}，
     * 所以追击逻辑不必知道目标是哪一类（见 {@code MovementService} 的 CHASE 分支）。
     */
    private Monster targetMonster;
    private double targetX;
    private double targetY;
    private double targetZ;
    private double patrolX; // 巡逻目标点
    private double patrolZ;
}
