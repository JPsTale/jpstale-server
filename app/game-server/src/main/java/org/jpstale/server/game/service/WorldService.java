package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerMoveState;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.C2S_PlayerMove;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_MapSwitched;
import org.jpstale.server.proto.base.S2C_PlayerMove;
import org.jpstale.server.proto.base.ServerMessage;
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

    @Autowired
    private PlayerService playerService;

    /** 边界门槛的服务端权威校验（防作弊）；给玩家的提示由客户端本地拦截负责，避免双份提示 */
    private final java.util.Map<Long, Long> gateLogAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long GATE_LOG_MS = 5000;

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
            // 等级门槛（原版：等级不够最多只能跑到地图边缘）。判定与客户端本地拦截、
            // 与传送入口（TeleportService）用的是**同一份** `MapManager.canEnter`。
            // 这里**不换图**：客户端位置权威，人已被客户端挡在门口；服务端只做权威侧拒绝 +
            // 节流日志（给玩家的可见提示由客户端出，避免同一句提示发两遍）。
            org.jpstale.server.game.model.Player p = playerService.getPlayer(session);
            if (p != null) {
                MapManager.EnterDeny deny = mapManager.canEnter(p.getLevel(), targetMap);
                if (deny != MapManager.EnterDeny.OK) {
                    long now = System.currentTimeMillis();
                    Long last = gateLogAt.get(session.getCharacterId());
                    if (last == null || now - last > GATE_LOG_MS) {
                        gateLogAt.put(session.getCharacterId(), now);
                        log.info("BOUNDARY {} 试图进入受限地图 {}（level {} < levelreq {} / {}）→ 拒绝换图，位置 ({},{})",
                                p.getName(), targetMap, p.getLevel(), mapManager.levelReqOf(targetMap), deny,
                                Math.round(e.getX()), Math.round(e.getZ()));
                    }
                    return;
                }
            }
            switchMap(session, targetMap);
        }
    }

    /**
     * 报文入口：客户端位置上权威（C2S_PlayerMove{position, angle, mode}）。
     * 只把上报存入 session.pendingMove —— 实际"限速校验 + AOI + 广播"由核心 loop 的
     * movementService.tickPlayers() 消费，保证位置只在一个线程（核心 loop）上被应用，
     * 与怪物 AI / 跨图判定无数据竞争。
     */
    @GamePacketHandler(ClientMessage.PLAYER_MOVE_FIELD_NUMBER)
    public void handleMove(PlayerSession session, ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        // 死亡躺下期间不接受移动（原版 DEAD 时点击/移动均无效）——
        // 否则客户端仍按本地预测往前跑，服务端却把你定在尸体处，两边位置分叉。
        PlayerEntity dead = session.getEntity();
        if (dead != null && dead.isDead()) {
            return;
        }
        C2S_PlayerMove move = message.getPlayerMove();
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
        // 该玩家"正播的哪一条动画"：原样透传，旁观者直接播同一条（服务端不解释其含义）
        session.setPendingMoveAnimIndex(move.getAnimIndex());
        session.setPendingMoveAnimClip(move.getAnimClip());
        session.noteMoveReceived();   // 诊断计数：收 / 应用 / 拒绝 三个数字一起看才分得清“没收到”与“被丢了”
        session.setPendingMoveArrivalMs(System.currentTimeMillis());   // 供限速用：见 MovementService 的 dt 说明
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

        // 通知客户端切图（protobuf，S2C_MapSwitched）：客户端对齐 currentMapId 并同步区域/音频/姿态
        session.send(ServerMessage.newBuilder()
            .setMapSwitched(S2C_MapSwitched.newBuilder()
                .setPlayerId(session.getCharacterId())
                .setFromMapId(oldMapId)
                .setMapId(newMapId)
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) newX).setY((float) e.getY()).setZ((float) newZ))
                .build())
            .build());

        // 切图后广播权威位置（含自己）：即便坐标未变，也同步一次让客户端刷新 amount/anim
        broadcastMove(session, e);
    }

    /**
     * 向视野内玩家（含自己）广播权威位置。数据读 PlayerEntity。
     */
    private void broadcastMove(PlayerSession session, PlayerEntity e) {
        int animState = animStateOf(e.getMoveState());
        ServerMessage moveMessage = ServerMessage.newBuilder()
            .setPlayerMove(S2C_PlayerMove.newBuilder()
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