package org.jpstale.server.game.network;

/**
 * 会话状态机（对应 plans/phase-2-login.md 4.2）
 *
 * <pre>
 * CONNECTED → LOGGED_IN → SERVER_SELECTED → CHARACTER_SELECTED → PLAYING
 *    ↑           |              |                 |                 |
 *    └───────────┴──────────────┴─────────────────┴─────────────────┘ (登出)
 * </pre>
 *
 * 状态含义：
 * <ul>
 *   <li>CONNECTED：已连接，未登录</li>
 *   <li>LOGGED_IN：账号已通过验证，待选择服务器</li>
 *   <li>SERVER_SELECTED：已选择服务器，待选择角色</li>
 *   <li>CHARACTER_SELECTED：已选择角色，待进入地图</li>
 *   <li>PLAYING：游戏中（已加入 AOI）</li>
 * </ul>
 */
public enum SessionState {
    CONNECTED,
    LOGGED_IN,
    SERVER_SELECTED,
    CHARACTER_SELECTED,
    PLAYING;

    /** 是否已登录（非 CONNECTED 即已登录） */
    public boolean isLoggedIn() {
        return this != CONNECTED;
    }

    /** 是否已在游戏中 */
    public boolean isPlaying() {
        return this == PLAYING;
    }

    /** 是否已达到（含）指定阶段，用于状态前置校验 */
    public boolean atLeast(SessionState required) {
        return this.ordinal() >= required.ordinal();
    }

    /** 是否正处于指定阶段 */
    public boolean is(SessionState expected) {
        return this == expected;
    }
}
