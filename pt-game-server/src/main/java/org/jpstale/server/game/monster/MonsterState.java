package org.jpstale.server.game.monster;

/**
 * 怪物状态
 */
public enum MonsterState {
    IDLE,       // 待机
    PATROL,     // 巡逻
    CHASE,      // 追击
    ATTACK,     // 攻击
    RETURN,     // 返回
    DEAD        // 死亡
}
