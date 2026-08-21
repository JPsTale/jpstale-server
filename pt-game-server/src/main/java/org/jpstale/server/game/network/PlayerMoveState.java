package org.jpstale.server.game.network;

/**
 * 玩家移动状态机。
 *
 * <pre>
 *          ┌─────────────────────────────────────────────┐
 *          │                                             │
 *          ▼                                             │
 *        IDLE ──(输入方向)──► WALK ──(Shift奔跑)──► RUN  │
 *          ▲                  │  ▲                    │  │
 *          │  (松开方向)        │  │ (松开Shift)         │  │
 *          │                  ▼  │                    ▼  │
 *          │                 (停止)─────────────────────►│
 *          │                                             │
 *          ├──(开始攻击)──► ATTACK ◄──(技能/被击保持)──────┘
 *          │
 *          └──(HP=0)──► DEAD ──(复活)──► IDLE
 * </pre>
 *
 * 状态含义：
 * <ul>
 *   <li>IDLE：待机，不移动</li>
 *   <li>WALK：行走（walk 速度）</li>
 *   <li>RUN：奔跑（run 速度）</li>
 *   <li>ATTACK：攻击中（攻击动画/动作期间不移动）</li>
 *   <li>DEAD：死亡（无法移动/攻击）</li>
 * </ul>
 */
public enum PlayerMoveState {
    IDLE,
    WALK,
    RUN,
    ATTACK,
    DEAD;

    /** 是否正在移动（WALK/RUN） */
    public boolean isMoving() {
        return this == WALK || this == RUN;
    }

    /** 是否奔跑（RUN） */
    public boolean isRunning() {
        return this == RUN;
    }

    /** 是否可移动（非 ATTACK/DEAD） */
    public boolean canMove() {
        return this == IDLE || this == WALK || this == RUN;
    }
}
