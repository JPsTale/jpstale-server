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

    @Autowired
    private MapRegionService mapRegionService;

    /**
     * 主循环 tick：主动检查所有在线玩家当前坐标是否已跨入其他地图。
     * 由 GameServer.tick() 在 movementService.tickPlayers() 之后调用。
     * <p>
     * 服务端权威移动后，玩家位置由 MovementService 更新；跨图判定收拢在此，
     * 依据 PlayerSession 当前坐标主动判定，无需 MovementService 反向调用本服务。
     */
    public void tick() {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session == null || !session.isPlaying()) continue;
            checkMapSwitch(session);
        }
    }

    /**
     * 跨图检测：AABB 快速反查 + 重叠时 GetHeight 精确判定
     * （对齐原版 FindStageField：先 AABB 粗筛，交界处用地形面精确判定）
     */
    private void checkMapSwitch(PlayerSession session) {
        int currentMapId = session.getCurrentMapId();
        int targetMap = mapRegionService.findMapPrecise(currentMapId, session.getX(), session.getZ());
        if (targetMap >= 0 && targetMap != currentMapId) {
            switchMap(session, targetMap);
        }
    }

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
        session.setZ(newZ);
        // Y 权威用地形高度（客户端/前端不提供真实 Y，高度差用于怪物视野判定）
        session.setY(mapRegionService.getHeight(session.getCurrentMapId(), newX, newZ));

        // 验证位置是否有效
        int mapId = session.getCurrentMapId();
        if (!mapManager.isValidPosition(mapId, newX, newZ)) {
            log.warn("Invalid position for player {}: ({}, {}, {})",
                session.getCharacterName(), newX, newY, newZ);
            return;
        }

        // 更新 AOI
        aoiManager.onPlayerMove(session, newX, newZ);

        // 跨图检测（对齐原版 FindStageField）
        checkMapSwitch(session);

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

/**
     * 切换地图：无缝大世界，玩家坐标不变（从边界走/跑进入），只更新 mapId + AOI 换图 + 通知客户端。
     */
    public void switchMap(PlayerSession session, int newMapId) {
        int oldMapId = session.getCurrentMapId();
        log.info("Player {} switching map {} -> {} at ({}, {})",
            session.getCharacterName(), oldMapId, newMapId,
            Math.round(session.getX()), Math.round(session.getZ()));

        double newX = session.getX();
        double newZ = session.getZ();

        // 移出旧图 AOI
        aoiManager.removePlayer(session);

        // 更新地图（坐标保持连续不变）
        session.setCurrentMapId(newMapId);

        // 重新加入 AOI
        aoiManager.addPlayer(session, newX, newZ);

        // 通知客户端切图（前端切换地图背景/刷怪，坐标不变）
        session.sendText("{\"type\":\"game.mapSwitched\",\"data\":{"
            + "\"fromMapId\":" + oldMapId
            + ",\"mapId\":" + newMapId
            + ",\"x\":" + newX
            + ",\"z\":" + newZ
            + "}}");
    }
}