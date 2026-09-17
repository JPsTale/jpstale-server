package org.jpstale.server.game.network;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器
 * 管理所有客户端连接的 Session
 */
@Slf4j
@Component
public class SessionManager {

    private final Map<Channel, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, PlayerSession> sessionsByAccountId = new ConcurrentHashMap<>();
    private final Map<Long, PlayerSession> sessionsByCharacterId = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> sessionsByCharacterName = new ConcurrentHashMap<>();

    /**
     * 创建新的 Session
     */
    public PlayerSession createSession(Channel channel) {
        PlayerSession session = new PlayerSession(channel);
        sessions.put(channel, session);
        log.debug("Created session for channel: {}", channel.remoteAddress());
        return session;
    }

    /**
     * 获取 Session
     */
    public PlayerSession getSession(Channel channel) {
        return sessions.get(channel);
    }

    /**
     * 根据账号ID获取 Session
     */
    public PlayerSession getSessionByAccountId(long accountId) {
        return sessionsByAccountId.get(accountId);
    }

    /**
     * 根据角色ID获取 Session
     */
    public PlayerSession getSessionByCharacterId(long characterId) {
        return sessionsByCharacterId.get(characterId);
    }

    /**
     * 根据角色名获取 Session
     */
    public PlayerSession getSessionByCharacterName(String characterName) {
        return sessionsByCharacterName.get(characterName);
    }

    /**
     * 绑定账号ID到 Session
     */
    public void bindAccountId(Channel channel, long accountId) {
        PlayerSession session = sessions.get(channel);
        if (session != null) {
            session.setAccountId(accountId);
            sessionsByAccountId.put(accountId, session);
            log.debug("Bound account {} to channel: {}", accountId, channel.remoteAddress());
        }
    }

    /**
     * 绑定角色ID到 Session
     */
    public void bindCharacterId(Channel channel, long characterId, String characterName) {
        PlayerSession session = sessions.get(channel);
        if (session != null) {
            session.setCharacterId(characterId);
            session.setCharacterName(characterName);
            session.setState(SessionState.CHARACTER_SELECTED);
            sessionsByCharacterId.put(characterId, session);
            sessionsByCharacterName.put(characterName, session);
            log.debug("Bound character {} ({}) to channel: {}", characterId, characterName, channel.remoteAddress());
        }
    }

    /**
     * 解除账号/角色绑定（登出用），但保留 channel session
     */
    public void unbind(Channel channel) {        PlayerSession session = sessions.get(channel);
        if (session == null) {
            return;
        }
        if (session.getAccountId() != null) {
            removeIfSame(sessionsByAccountId, session.getAccountId(), session);
        }
        if (session.getCharacterId() != null) {
            removeIfSame(sessionsByCharacterId, session.getCharacterId(), session);
            removeIfSame(sessionsByCharacterName, session.getCharacterName(), session);
        }
        session.setAccountId(null);
        session.setCharacterId(null);
        session.setCharacterName(null);
        session.setState(SessionState.CONNECTED);
        log.debug("Unbound account/character for channel: {}", channel.remoteAddress());
    }

        /**
     * 游戏内退出到角色选择大厅：仅清除角色绑定、状态回 SERVER_SELECTED，保留账号绑定与连接
     */
    public void unbindCharacter(Channel channel) {
        PlayerSession session = sessions.get(channel);
        if (session == null) {
            return;
        }
        if (session.getCharacterId() != null) {
            removeIfSame(sessionsByCharacterId, session.getCharacterId(), session);
            removeIfSame(sessionsByCharacterName, session.getCharacterName(), session);
        }
        session.setCharacterId(null);
        session.setCharacterName(null);
        session.setState(SessionState.SERVER_SELECTED);
        log.debug("Unbound character (back to lobby) for channel: {}", channel.remoteAddress());
    }

    /**
     * 该会话是否仍是它那个角色的**当前持有者** —— 过期连接的清理必须据此判定（唯一判定）。
     *
     * 刷新页面时旧连接的 `channelInactive` / 登出 / 回选角 常常**晚于**新连接的登录：此时
     * `sessionsByCharacterId[cid]` 已经指向新会话，旧会话的任何"按角色清理"（AOI 摘除、在线缓存移除、
     * 落库、解绑索引）都会打掉**新会话**的世界状态 —— 症状是收不到怪物 Appear/Move/Death、
     * 服务端不再应用移动上报（用户 2026-09-14 实测）。
     *
     * 所以：过期会话只允许清理**它自己**（清 session.entity、状态回 CONNECTED、关自己的连接），
     * 凡是"按 charId 定位"的动作都必须先过这一关。
     */
    public boolean isCurrentForCharacter(PlayerSession session) {
        Long cid = session != null ? session.getCharacterId() : null;
        return cid != null && sessionsByCharacterId.get(cid) == session;
    }

    /**
     * 只当索引**确实指向该会话**时才删除（按引用比较）。
     *
     * ⚠ 这里**不能**用 `Map.remove(key, value)`：`PlayerSession` 的 equals/hashCode 是**按字段值**算的
     * （`@EqualsAndHashCode(exclude = {"channel","entity","pending"})` —— 特意排除了 channel），
     * 于是"同一角色的新旧两个会话"在字段上完全相等、`old.equals(live)` 为真 ⇒
     * 两参 remove 会把**新会话**那条删掉，正是本类要防的事（本方法的第一版就是这么写的，
     * 被 SessionIdentityTest 当场抓出：旧连接一关，新会话的角色映射就没了）。
     */
    private static <K> void removeIfSame(Map<K, PlayerSession> map, K key, PlayerSession session) {
        if (key == null) {
            return;
        }
        map.computeIfPresent(key, (k, cur) -> cur == session ? null : cur);
    }

    /**
     * 移除 Session
     *
     * 三个索引都走 {@link #removeIfSame}：过期连接关闭时不得动到新会话的映射。
     */
    public void removeSession(Channel channel) {
        PlayerSession session = sessions.remove(channel);
        if (session != null) {
            if (session.getAccountId() != null) {
                removeIfSame(sessionsByAccountId, session.getAccountId(), session);
            }
            if (session.getCharacterId() != null) {
                removeIfSame(sessionsByCharacterId, session.getCharacterId(), session);
                removeIfSame(sessionsByCharacterName, session.getCharacterName(), session);
            }
            log.debug("Removed session for channel: {}", channel.remoteAddress());
        }
    }

    /**
     * 获取所有 Session
     */
    public Collection<PlayerSession> getAllSessions() {
        return sessions.values();
    }

    /**
     * 把**所有**会话本 tick 累积的消息一次发出（合批，见 PlayerSession.flushPending）。
     *
     * 由 GameServer.tick 每 tick 调一次（20Hz）。刻意遍历**全部**会话而不只是 playing 的：
     * 登录/选角阶段也可能有积压（虽然那几类走了即时通道，但这里不能依赖"谁在游戏里"的假设
     * 来决定要不要发 —— 漏发的症状是"客户端永远收不到某条消息"，且没有任何报错）。
     */
    public void flushAll() {
        for (PlayerSession session : sessions.values()) {
            session.flushPending();
        }
    }

    /**
     * 获取在线玩家数量
     */
    public int getOnlineCount() {
        return sessions.size();
    }

    /**
     * 获取正在游戏的玩家数量
     */
    public int getPlayingCount() {
        int count = 0;
        for (PlayerSession session : sessions.values()) {
            if (session.isPlaying()) {
                count++;
            }
        }
        return count;
    }
}
