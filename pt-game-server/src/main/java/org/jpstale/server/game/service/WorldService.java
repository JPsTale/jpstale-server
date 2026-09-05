package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerMoveState;
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
     * 报文入口：玩家移动意图（C2S_PlayerMove{angle, mode}）。
     * 只记录方向 + 走/跑状态，不采客户端位置 —— 位置由 MovementService.tickPlayers 权威推进。
     */
    @GamePacketHandler(MessageProto.ClientMessage.PLAYER_MOVE_FIELD_NUMBER)
    public void handleMove(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }

        MessageProto.C2S_PlayerMove move = message.getPlayerMove();
        session.setMoveAngle(move.getAngle());
        session.setMoveState(PlayerMoveState.fromMode(move.getMode()));
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
        // 跨图：重发视野内外观快照（双方互见）
        aoiManager.onPlayerEnter(session);

        // 通知客户端切图（前端切换地图背景/刷怪，坐标不变）
        session.sendText("{\"type\":\"game.mapSwitched\",\"data\":{"
            + "\"fromMapId\":" + oldMapId
            + ",\"mapId\":" + newMapId
            + ",\"x\":" + newX
            + ",\"z\":" + newZ
            + "}}");

        // 切图后广播权威位置（含自己）：即便坐标未变，也同步一次让客户端刷新 amount/anim
        broadcastMove(session);
    }

    /**
     * 向视野内玩家（含自己）广播权威位置。
     */
    private void broadcastMove(PlayerSession session) {
        int animState = animStateOf(session.getMoveState());
        MessageProto.ServerMessage moveMessage = MessageProto.ServerMessage.newBuilder()
            .setPlayerMove(MessageProto.S2C_PlayerMove.newBuilder()
                .setPlayerId(session.getCharacterId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) session.getX())
                    .setY((float) session.getY())
                    .setZ((float) session.getZ())
                    .build())
                .setAngle((float) session.getMoveAngle())
                .setAnimState(animState)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();

        for (PlayerSession nearbySession : aoiManager.getNearbyPlayers(session.getX(), session.getZ())) {
            nearbySession.send(moveMessage);
        }
    }

    /**
     * moveState → anim_state（客户端 STATE 枚举值：STAND/WALK/RUN）。
     * IDLE/ATTACK/DEAD 统一归 STAND（一次性行为动画由 S2C_PlayerState 事件驱动）。
     */
    private static int animStateOf(PlayerMoveState state) {
        return switch (state) {
            case WALK -> 0x0050;
            case RUN -> 0x0060;
            default -> 0x0040;
        };
    }
}