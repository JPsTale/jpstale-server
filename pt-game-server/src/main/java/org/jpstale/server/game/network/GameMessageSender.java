package org.jpstale.server.game.network;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息发送器实现
 * 支持单播、广播、区域广播、组播
 */
@Slf4j
@Component
public class GameMessageSender implements MessageSender {

    @Autowired
    private SessionManager sessionManager;

    // AOI 管理器将在 Phase 3 实现，暂时用简单广播
    // @Autowired
    // private AOIManager aoiManager;

    @Override
    public void sendToPlayer(long playerId, MessageProto.ServerMessage message) {
        PlayerSession session = sessionManager.getSessionByCharacterId(playerId);
        if (session != null && session.isPlaying()) {
            session.send(message);
        }
    }

    @Override
    public void broadcastToMap(int mapId, MessageProto.ServerMessage message) {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session.isPlaying()) {
                // TODO: 检查玩家是否在指定地图
                session.send(message);
            }
        }
    }

    @Override
    public void broadcastToArea(int mapId, float centerX, float centerZ, float range, MessageProto.ServerMessage message) {
        // TODO: Phase 3 实现 AOI 后，只发送给视野内玩家
        // 暂时广播给所有玩家
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session.isPlaying()) {
                // TODO: 检查玩家位置是否在范围内
                session.send(message);
            }
        }
    }

    @Override
    public void multicast(List<Long> playerIds, MessageProto.ServerMessage message) {
        for (Long playerId : playerIds) {
            sendToPlayer(playerId, message);
        }
    }

    @Override
    public void broadcastToAll(MessageProto.ServerMessage message) {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            session.send(message);
        }
    }
}
