package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
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
        PlayerEntity e = session.getEntity();
        if (e == null) return;
        int currentMapId = e.getMapId();
        int targetMap = mapRegionService.findMapPrecise(currentMapId, e.getX(), e.getZ());
        if (targetMap >= 0 && targetMap != currentMapId) {
            switchMap(session, targetMap);
        }
    }

    /**
     * 报文入口：客户端位置上权威（C2S_PlayerMove{position, angle, mode}）。
     * 只把上报存入 session.pendingMove —— 实际"限速校验 + AOI + 广播"由核心 loop 的
     * movementService.tickPlayers() 消费，保证位置只在一个线程（核心 loop）上被应用，
     * 与怪物 AI / 跨图判定无数据竞争。
     */
    @GamePacketHandler(MessageProto.ClientMessage.PLAYER_MOVE_FIELD_NUMBER)
    public void handleMove(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        MessageProto.C2S_PlayerMove move = message.getPlayerMove();
        if (!move.hasPosition()) {
            return;
        }
        CommonProto.Position pos = move.getPosition();
        session.setPendingMoveX(pos.getX());
        session.setPendingMoveY(pos.getY());
        session.setPendingMoveZ(pos.getZ());
        session.setPendingMoveAngle(move.getAngle());
        session.setPendingMoveMode(move.getMode());
        session.setPendingMoveAnimState(move.getAnimState());
    }

/**
     * 切换地图：无缝大世界，玩家坐标不变（从边界走/跑进入），只更新 mapId + AOI 换图 + 通知客户端。
     */
    public void switchMap(PlayerSession session, int newMapId) {
        PlayerEntity e = session.getEntity();
        if (e == null) return;
        int oldMapId = e.getMapId();
        log.info("Player {} switching map {} -> {} at ({}, {})",
            session.getCharacterName(), oldMapId, newMapId,
            Math.round(e.getX()), Math.round(e.getZ()));

        double newX = e.getX();
        double newZ = e.getZ();

        // 移出旧图 AOI(实体化成员)
        aoiManager.removePlayer(e);

        // 更新地图（坐标保持连续不变）
        e.setMapId(newMapId);

        // 重新加入 AOI
        aoiManager.addPlayer(e);
        // 跨图：重发视野内外观快照（双方互见）
        aoiManager.onPlayerEnter(e);

        // 通知客户端切图（前端切换地图背景/刷怪，坐标不变）
        session.sendText("{\"type\":\"game.mapSwitched\",\"data\":{"
            + "\"fromMapId\":" + oldMapId
            + ",\"mapId\":" + newMapId
            + ",\"x\":" + newX
            + ",\"z\":" + newZ
            + "}}");

        // 切图后广播权威位置（含自己）：即便坐标未变，也同步一次让客户端刷新 amount/anim
        broadcastMove(session, e);
    }

    /**
     * 向视野内玩家（含自己）广播权威位置。数据读 PlayerEntity。
     */
    private void broadcastMove(PlayerSession session, PlayerEntity e) {
        int animState = animStateOf(e.getMoveState());
        MessageProto.ServerMessage moveMessage = MessageProto.ServerMessage.newBuilder()
            .setPlayerMove(MessageProto.S2C_PlayerMove.newBuilder()
                .setPlayerId(session.getCharacterId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) e.getX())
                    .setY((float) e.getY())
                    .setZ((float) e.getZ())
                    .build())
                .setAngle((float) e.getAngle())
                .setAnimState(animState)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();

        for (PlayerEntity nearby : aoiManager.getNearbyPlayers(e.getX(), e.getZ())) {
            PlayerSession ns = nearby.getSession();
            if (ns != null) ns.send(moveMessage);
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