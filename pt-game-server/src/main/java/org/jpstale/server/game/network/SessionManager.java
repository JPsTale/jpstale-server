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
    public void unbind(Channel channel) {
        PlayerSession session = sessions.get(channel);
        if (session == null) {
            return;
        }
        if (session.getAccountId() != null) {
            sessionsByAccountId.remove(session.getAccountId());
        }
        if (session.getCharacterId() != null) {
            sessionsByCharacterId.remove(session.getCharacterId());
            sessionsByCharacterName.remove(session.getCharacterName());
        }
        session.setAccountId(null);
        session.setCharacterId(null);
        session.setCharacterName(null);
        session.setState(SessionState.CONNECTED);
        log.debug("Unbound account/character for channel: {}", channel.remoteAddress());
    }

    /**
     * 移除 Session
     */
    public void removeSession(Channel channel) {
        PlayerSession session = sessions.remove(channel);
        if (session != null) {
            if (session.getAccountId() != null) {
                sessionsByAccountId.remove(session.getAccountId());
            }
            if (session.getCharacterId() != null) {
                sessionsByCharacterId.remove(session.getCharacterId());
                sessionsByCharacterName.remove(session.getCharacterName());
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
