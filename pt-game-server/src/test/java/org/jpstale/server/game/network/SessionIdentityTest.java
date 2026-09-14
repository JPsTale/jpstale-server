package org.jpstale.server.game.network;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 会话身份判定回归（用户 2026-09-14 实测两个症状的根因）。
 *
 * 场景：**刷新页面**。新连接先完成登录并把角色绑定写进 `sessionsByCharacterId`，
 * 旧连接的 `channelInactive` / 登出 / 回选角 往往**晚于**它才到。
 * 这些清理全是"按 charId 定位"的（AOI 摘除、在线缓存移除、落库、解绑索引），
 * 若不过身份判定，就会把**新会话**的世界状态一起打掉 ——
 * 症状：客户端收不到怪物 Appear/Move/Death（怪死了客户端不知道、
 * 刷新后看不到附近的怪却一直掉血），服务端也不再应用其移动上报。
 *
 * 本测试钉住三条不变量：
 *  1. `isCurrentForCharacter` 只对"当前持有该角色的那个会话"为真；
 *  2. 旧连接的 `removeSession` / `unbind` **不得**删掉新会话的角色映射；
 *  3. 当前持有者关闭时映射照常清掉（重登时重新绑定，不泄漏）。
 */
public class SessionIdentityTest {

    private static final long CHAR_ID = 78L;
    private static final String CHAR_NAME = "aglob";

    @Test
    public void staleConnectionMustNotEvictLiveSession() {
        SessionManager sm = new SessionManager();
        EmbeddedChannel chOld = new EmbeddedChannel();
        EmbeddedChannel chNew = new EmbeddedChannel();
        PlayerSession old = sm.createSession(chOld);
        PlayerSession live = sm.createSession(chNew);

        // 同一角色先后绑到两条连接（刷新：新连接先登录，旧连接的关闭事件随后才到）
        sm.bindCharacterId(chOld, CHAR_ID, CHAR_NAME);
        sm.bindCharacterId(chNew, CHAR_ID, CHAR_NAME);

        assertTrue("新会话应是当前持有者", sm.isCurrentForCharacter(live));
        assertFalse("旧会话已不是当前持有者", sm.isCurrentForCharacter(old));

        // 旧连接关闭 —— 不得动到新会话的映射
        sm.removeSession(chOld);
        assertSame("旧连接关闭后，角色映射仍应指向新会话",
            live, sm.getSessionByCharacterId(CHAR_ID));
        assertSame("角色名映射同理", live, sm.getSessionByCharacterName(CHAR_NAME));

        // 旧连接迟到的登出（unbind）同理
        sm.unbind(chOld);
        assertSame("过期会话解绑不得删掉新会话的角色映射",
            live, sm.getSessionByCharacterId(CHAR_ID));
    }

    @Test
    public void unboundSessionOwnsNothing() {
        SessionManager sm = new SessionManager();
        PlayerSession s = sm.createSession(new EmbeddedChannel());
        assertFalse("没绑角色的会话不算任何角色的持有者", sm.isCurrentForCharacter(s));
        assertFalse("null 会话必须安全返回 false", sm.isCurrentForCharacter(null));
    }

    @Test
    public void currentSessionTeardownStillRemovesMapping() {
        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = sm.createSession(ch);
        sm.bindCharacterId(ch, CHAR_ID, CHAR_NAME);
        assertTrue(sm.isCurrentForCharacter(s));

        sm.removeSession(ch);
        assertNull("当前持有者关闭后映射照常清掉（重登重新绑定，不残留）",
            sm.getSessionByCharacterId(CHAR_ID));
    }

    /** 顶号顺序（同上但用 unbindCharacter：回选角只清角色、保留账号） */
    @Test
    public void staleConnectionBackToLobbyMustNotEvictLiveSession() {
        SessionManager sm = new SessionManager();
        EmbeddedChannel chOld = new EmbeddedChannel();
        EmbeddedChannel chNew = new EmbeddedChannel();
        sm.createSession(chOld);
        PlayerSession live = sm.createSession(chNew);
        sm.bindCharacterId(chOld, CHAR_ID, CHAR_NAME);
        sm.bindCharacterId(chNew, CHAR_ID, CHAR_NAME);

        sm.unbindCharacter(chOld);
        assertSame("过期会话回选角不得删掉新会话的角色映射",
            live, sm.getSessionByCharacterId(CHAR_ID));
    }
}
