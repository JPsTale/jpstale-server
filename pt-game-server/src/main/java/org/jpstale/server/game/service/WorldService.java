package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 世界服务
 * 移动处理、地图/AOI（方案1：handler 并入 service）
 */
@Slf4j
@Component
public class WorldService {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private MapManager mapManager;

    /**
     * 报文入口：玩家移动
     */
    @GamePacketHandler(MessageProto.ClientMessage.PLAYER_MOVE_FIELD_NUMBER)
    public void handleMove(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_PlayerMove move = message.getPlayerMove();

        if (session == null || !session.isPlaying()) {
            return;
        }

        // 获取新位置
        float newX = move.getNewPosition().getX();
        float newY = move.getNewPosition().getY();
        float newZ = move.getNewPosition().getZ();

        // 更新 Session 位置（用于怪物刷怪 proximity check）
        session.setX(newX);
        session.setY(newY);
        session.setZ(newZ);

        // 验证位置是否有效
        int mapId = session.getCurrentMapId() > 0 ? session.getCurrentMapId() : 1;
        if (!mapManager.isValidPosition(mapId, newX, newZ)) {
            log.warn("Invalid position for player {}: ({}, {}, {})",
                session.getCharacterName(), newX, newY, newZ);
            return;
        }

        // 更新 AOI
        aoiManager.onPlayerMove(session, newX, newZ);

        // 广播移动给视野内的玩家
        MessageProto.ServerMessage moveMessage = MessageProto.ServerMessage.newBuilder()
            .setPlayerMove(MessageProto.S2C_PlayerMove.newBuilder()
                .setPlayerId(session.getCharacterId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX(newX)
                    .setY(newY)
                    .setZ(newZ)
                    .build())
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();

        for (PlayerSession nearbySession : aoiManager.getNearbyPlayers(newX, newZ)) {
            if (nearbySession.getCharacterId() != session.getCharacterId()) {
                nearbySession.send(moveMessage);
            }
        }
    }
}