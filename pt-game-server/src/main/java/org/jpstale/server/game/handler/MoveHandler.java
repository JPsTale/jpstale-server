package org.jpstale.server.game.handler;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.world.AOIManager;
import org.jpstale.server.game.world.MapManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 玩家移动处理器
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.PLAYER_MOVE_FIELD_NUMBER)
public class MoveHandler implements PacketHandler {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private MapManager mapManager;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        MessageProto.C2S_PlayerMove move = message.getPlayerMove();
        PlayerSession session = sessionManager.getSession(channel);

        if (session == null || !session.isPlaying()) {
            return;
        }

        // 获取新位置
        float newX = move.getNewPosition().getX();
        float newY = move.getNewPosition().getY();
        float newZ = move.getNewPosition().getZ();

        // 验证位置是否有效
        // TODO: 获取玩家当前地图ID
        int mapId = 1; // 默认地图
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
