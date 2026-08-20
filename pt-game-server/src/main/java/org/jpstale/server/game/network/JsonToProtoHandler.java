package org.jpstale.server.game.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.GameMap;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterSpawnConfig;
import org.jpstale.server.game.model.MonsterWave;
import org.jpstale.server.game.model.SpawnPoint;
import org.jpstale.server.game.service.MapManager;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.AccountService;
import org.jpstale.server.game.service.MapRegionService;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket JSON 编解码适配器。
 * <p>
 * inbound:  TextWebSocketFrame(JSON) → {@link MessageProto.ClientMessage}（标准游戏命令）
 *           或拦截处理 Web 专用命令（serverList/selectServer/enterMap）。
 * outbound: {@link MessageProto.ServerMessage} → TextWebSocketFrame(JSON)。
 */
@Slf4j
@Sharable
@Component
public class JsonToProtoHandler extends ChannelDuplexHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MapManager mapManager;

    @Autowired
    private MonsterSpawnService monsterSpawnService;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ReconnectionManager reconnectionManager;

    @Autowired
    private MapRegionService mapRegionService;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof WebSocketFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (!(frame instanceof TextWebSocketFrame text)) {
            ctx.fireChannelRead(frame);
            return;
        }

        PlayerSession session = sessionManager.getSession(ctx.channel());
        String content = text.text();

        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (Exception e) {
            sendError(ctx, -1, "Invalid JSON: " + e.getMessage());
            return;
        }

        String type = root.path("type").asText("");
        JsonNode data = root.path("data");

        // ---- Web 专用命令（不经过 proto 路由） ----
        switch (type) {
            case "auth.serverList" -> {
                sendServerList(ctx);
                return;
            }
            case "auth.reconnect" -> {
                handleReconnect(ctx, session, data);
                return;
            }
            case "auth.selectServer" -> {
                handleSelectServer(ctx, session, data);
                return;
            }
            case "game.enterMap" -> {
                handleEnterMap(ctx, session, data);
                return;
            }
            case "map.list" -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (GameMap gm : mapManager.getMaps().values()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("mapId", gm.getId());
                    m.put("name", gm.getName() != null ? gm.getName() : ("Map " + gm.getId()));
                    m.put("shortName", gm.getShortName() != null ? gm.getShortName() : "");
                    list.add(m);
                }
                sendJson(ctx, Map.of("type", "map.list", "data", Map.of("maps", list)));
                return;
            }
            case "map.aabbs" -> {
                sendMapAabbs(ctx);
                return;
            }
            case "game.leave" -> {
                if (session != null) {
                    // 离开游戏 → 回到"已选角"阶段（保留登录态），移出 AOI
                    aoiManager.removePlayer(session);
                    session.setState(SessionState.CHARACTER_SELECTED);
                }
                sendJson(ctx, Map.of("type", "game.leave", "success", true));
                return;
            }
            default -> {
                // 标准 proto 命令，继续向下
            }
        }

        MessageProto.ClientMessage proto = toClientMessage(type, data);
        if (proto == null) {
            sendError(ctx, -1, "Unknown message type: " + type);
            return;
        }

        ctx.fireChannelRead(proto);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof MessageProto.ServerMessage serverMessage) {
            String json = serverMessageToJson(serverMessage);
            ctx.write(new TextWebSocketFrame(json), promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    // ==================== JSON → proto ====================

    private MessageProto.ClientMessage toClientMessage(String type, JsonNode data) {
        try {
            switch (type) {
                case "auth.login" -> {
                    return MessageProto.ClientMessage.newBuilder()
                        .setLoginRequest(MessageProto.C2S_LoginRequest.newBuilder()
                            .setUsername(data.path("username").asText(""))
                            .setPassword(data.path("password").asText(""))
                            .build())
                        .build();
                }
                case "auth.createCharacter" -> {
                    return MessageProto.ClientMessage.newBuilder()
                        .setCreateCharacter(MessageProto.C2S_CreateCharacter.newBuilder()
                            .setName(data.path("name").asText(""))
                            .setClassId(data.path("classId").asInt(1))
                            .build())
                        .build();
                }
                case "auth.selectCharacter" -> {
                    return MessageProto.ClientMessage.newBuilder()
                        .setSelectCharacter(MessageProto.C2S_SelectCharacter.newBuilder()
                            .setCharacterId(data.path("characterId").asLong(0))
                            .build())
                        .build();
                }
                case "auth.logout" -> {
                    return MessageProto.ClientMessage.newBuilder()
                        .setLogout(MessageProto.C2S_Logout.newBuilder().build())
                        .build();
                }
                case "game.move" -> {
                    return MessageProto.ClientMessage.newBuilder()
                        .setPlayerMove(MessageProto.C2S_PlayerMove.newBuilder()
                            .setNewPosition(CommonProto.Position.newBuilder()
                                .setX((float) data.path("x").asDouble(0))
                                .setY((float) data.path("y").asDouble(0))
                                .setZ((float) data.path("z").asDouble(0))
                                .build())
                            .build())
                        .build();
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: type={}", type, e);
            return null;
        }
    }

    // ==================== proto → JSON ====================

    private String serverMessageToJson(MessageProto.ServerMessage msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (msg.getPayloadCase()) {
            case LOGIN_RESPONSE -> {
                out.put("type", "auth.loginResult");
                MessageProto.S2C_LoginResponse r = msg.getLoginResponse();
                out.put("data", Map.of(
                    "success", r.getSuccess(),
                    "accountId", r.getAccountId(),
                    "errorCode", r.getErrorCode().getNumber(),
                    "errorMessage", r.getErrorMessage()));
            }
            case CHARACTER_LIST -> {
                out.put("type", "auth.characterList");
                List<Map<String, Object>> chars = new ArrayList<>();
                for (MessageProto.CharacterInfo c : msg.getCharacterList().getCharactersList()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("characterId", c.getCharacterId());
                    cm.put("name", c.getName());
                    cm.put("classId", c.getClassId());
                    cm.put("level", c.getLevel());
                    cm.put("mapId", c.getMapId());
                    cm.put("gold", c.getGold());
                    chars.add(cm);
                }
                out.put("data", Map.of("characters", chars));
            }
            case CREATE_CHARACTER_RESULT -> {
                out.put("type", "auth.createResult");
                MessageProto.S2C_CreateCharacterResult r = msg.getCreateCharacterResult();
                out.put("data", Map.of(
                    "success", r.getSuccess(),
                    "characterId", r.getCharacterId(),
                    "errorCode", r.getErrorCode().getNumber()));
            }
            case PLAYER_STATE -> {
                out.put("type", "auth.enterGame");
                MessageProto.S2C_PlayerState r = msg.getPlayerState();
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("playerId", r.getPlayerId());
                cm.put("mapId", r.getMapId());
                cm.put("level", r.getLevel());
                cm.put("gold", r.getGold());
                cm.put("exp", r.getExp());
                cm.put("hp", r.getHp());
                cm.put("maxHp", r.getMaxHp());
                cm.put("mp", r.getMp());
                cm.put("maxMp", r.getMaxMp());
                out.put("data", cm);
            }
            case PLAYER_MOVE -> {
                out.put("type", "game.moveAck");
                MessageProto.S2C_PlayerMove r = msg.getPlayerMove();
                out.put("data", Map.of(
                    "playerId", r.getPlayerId(),
                    "x", r.getPosition().getX(),
                    "z", r.getPosition().getZ()));
            }
            case MONSTER_APPEAR -> {
                out.put("type", "game.monsterAppear");
                out.put("data", Map.of("monster", monsterToMap(msg.getMonsterAppear())));
            }
            case MONSTER_DEATH -> {
                out.put("type", "game.monsterDeath");
                MessageProto.S2C_MonsterDeath r = msg.getMonsterDeath();
                out.put("data", Map.of(
                    "id", r.getMonsterId(),
                    "killerId", r.getKillerId(),
                    "exp", r.getExp(),
                    "gold", r.getGold()));
            }
            case MONSTER_DISAPPEAR -> {
                out.put("type", "game.monsterDisappear");
                out.put("data", Map.of("id", msg.getMonsterDisappear().getMonsterId()));
            }
            case ERROR -> {
                out.put("type", "error");
                MessageProto.S2C_Error r = msg.getError();
                out.put("data", Map.of(
                    "code", r.getErrorCode().getNumber(),
                    "message", r.getErrorMessage()));
            }
            default -> {
                out.put("type", msg.getPayloadCase().name().toLowerCase());
                out.put("data", msg.toString());
            }
        }
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"data\":{\"message\":\"serialize failed\"}}";
        }
    }

    private Map<String, Object> monsterToMap(MessageProto.S2C_MonsterAppear m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getMonsterId());
        map.put("templateId", m.getTemplateId());
        map.put("name", m.getName());
        map.put("level", m.getLevel());
        map.put("x", m.getPosition().getX());
        map.put("z", m.getPosition().getZ());
        map.put("hp", m.getHp());
        map.put("maxHp", m.getMaxHp());
        return map;
    }

    // ==================== Web 专用命令 ====================

    private void sendServerList(ChannelHandlerContext ctx) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("id", 1);
        server.put("name", "Local Game Server");
        server.put("online", true);
        sendJson(ctx, Map.of(
            "type", "auth.serverList",
            "data", Map.of("servers", List.of(server))));
    }

    /**
     * 下发全部地图的真实 AABB（来自 .smd RECT）。
     * 格式：{mapId: {name, xMin, xMax, zMin, zMax}}
     */
    private void sendMapAabbs(ChannelHandlerContext ctx) {
        Map<String, Object> aabbs = new LinkedHashMap<>();
        for (int i = 0; i < mapRegionService.size(); i++) {
            int[] a = mapRegionService.getAabb(i);
            if (a == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", mapManager.getMap(i) != null ? mapManager.getMap(i).getName() : ("Map " + i));
            m.put("xMin", a[0]);
            m.put("xMax", a[1]);
            m.put("zMin", a[2]);
            m.put("zMax", a[3]);
            aabbs.put(String.valueOf(i), m);
        }
        sendJson(ctx, Map.of("type", "map.aabbs", "data", Map.of("aabbs", aabbs)));
    }

    /**
     * 处理选服命令：校验状态 → 设 SERVER_SELECTED → 下发角色列表
     */
    private void handleSelectServer(ChannelHandlerContext ctx, PlayerSession session, JsonNode data) {
        if (session == null || !session.isLoggedIn()) {
            sendError(ctx, CommonProto.ErrorCode.NOT_LOGIN.getNumber(), "Not logged in");
            return;
        }
        // 允许：登录后首次选服(LOGGED_IN)，或已选服后刷新角色列表(SERVER_SELECTED)
        if (!session.getState().is(SessionState.LOGGED_IN)
            && !session.getState().is(SessionState.SERVER_SELECTED)) {
            sendError(ctx, CommonProto.ErrorCode.ALREADY_LOGIN.getNumber(), "Invalid state for server selection");
            return;
        }
        int serverId = data.path("serverId").asInt(1);
        if (serverId != 1) {
            sendError(ctx, CommonProto.ErrorCode.NOT_LOGIN.getNumber(), "Unknown server: " + serverId);
            return;
        }

        session.setState(SessionState.SERVER_SELECTED);
        sendJson(ctx, Map.of(
            "type", "auth.selectServer",
            "data", Map.of("success", true, "serverId", serverId)));

        // 下发角色列表
        accountService.sendCharacterList(session);
    }

    /**
     * 处理断线重连：验证 token → 恢复会话到游戏中
     */
    private void handleReconnect(ChannelHandlerContext ctx, PlayerSession session, JsonNode data) {
        if (session == null) {
            return;
        }
        // 仅允许未登录状态发起重连（防止已有登录时重复）
        if (session.isLoggedIn()) {
            sendError(ctx, CommonProto.ErrorCode.ALREADY_LOGIN.getNumber(), "Already logged in");
            return;
        }

        String token = data.path("token").asText("");
        ReconnectionManager.PendingReconnection pending = reconnectionManager.validateReconnectToken(token);
        if (pending == null) {
            sendError(ctx, CommonProto.ErrorCode.NOT_LOGIN.getNumber(), "Invalid or expired reconnect token");
            return;
        }

        // 恢复账号/角色绑定
        if (pending.accountId() != null) {
            session.setAccountId(pending.accountId());
            sessionManager.bindAccountId(session.getChannel(), pending.accountId());
        }
        if (pending.characterId() != null) {
            session.setCharacterId(pending.characterId());
            session.setCharacterName(pending.characterName());
            sessionManager.bindCharacterId(session.getChannel(), pending.characterId(), pending.characterName());
        }

        // 恢复位置与状态
        session.setCurrentMapId(pending.mapId());
        session.setX(pending.x());
        session.setY(pending.y());
        session.setZ(pending.z());
        session.setHp(pending.hp());
        session.setMaxHp(pending.maxHp());
        session.setMp(pending.mp());
        session.setMaxMp(pending.maxMp());
        session.setLevel(pending.level());
        session.setState(SessionState.PLAYING);

        // 重新加入 AOI
        aoiManager.addPlayer(session, pending.x(), pending.z());

        // 通知客户端重连成功（回到游戏）
        sendJson(ctx, Map.of(
            "type", "auth.reconnected",
            "data", Map.of(
                "success", true,
                "playerId", pending.characterId() != null ? pending.characterId() : 0,
                "mapId", pending.mapId(),
                "x", pending.x(),
                "z", pending.z())));

        log.info("Player reconnected: {} (map={})", pending.characterName(), pending.mapId());
    }

    private void handleEnterMap(ChannelHandlerContext ctx, PlayerSession session, JsonNode data) {
        if (session == null || !session.getState().atLeast(SessionState.CHARACTER_SELECTED)) {
            sendError(ctx, CommonProto.ErrorCode.NOT_LOGIN.getNumber(), "Not logged in / no character");
            return;
        }

        int mapId = data.path("mapId").asInt(session.getCurrentMapId());
        GameMap gameMap = mapManager.getMap(mapId);
        if (gameMap == null) {
            sendError(ctx, CommonProto.ErrorCode.INVALID_MAP.getNumber(), "Invalid map: " + mapId);
            return;
        }

        // 设置会话位置与地图
        session.setCurrentMapId(mapId);
        if (!data.has("x") && !data.has("z")) {
            List<SpawnPoint> points = gameMap.getSpawnPoints();
            if (!points.isEmpty()) {
                SpawnPoint sp = points.get(0);
                session.setX(sp.getX());
                session.setZ(sp.getZ());
            } else {
                // 无 DB 出生点：用 FieldMap 硬编码出生点/中心兜底，
                // 避免落到 (0,0) 掉进其他图（如 ric -> waste1）的区域
                int[] sp = null;
                if (mapId >= 0 && mapId < FieldMap.values().length) {
                    FieldMap fm = FieldMap.values()[mapId];
                    if (fm.startPoints != null && fm.startPoints.length > 0) {
                        sp = fm.startPoints[0];
                    } else {
                        sp = fm.center;
                    }
                }
                if (sp != null) {
                    session.setX(sp[0]);
                    session.setZ(sp[1]);
                } else {
                    session.setX(0);
                    session.setZ(0);
                }
            }
        } else {
            session.setX((float) data.path("x").asDouble(session.getX()));
            session.setZ((float) data.path("z").asDouble(session.getZ()));
        }

        aoiManager.addPlayer(session, session.getX(), session.getZ());
        session.setState(SessionState.PLAYING);

        List<Map<String, Object>> spawnPoints = new ArrayList<>();
        for (SpawnPoint sp : gameMap.getSpawnPoints()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sp.getId());
            m.put("x", sp.getX());
            m.put("z", sp.getZ());
            m.put("description", sp.getDescription() != null ? sp.getDescription() : "");
            spawnPoints.add(m);
        }

        MonsterSpawnConfig config = gameMap.getMonsterSpawnConfig();
        List<Map<String, Object>> waves = new ArrayList<>();
        if (config != null) {
            for (MonsterWave wave : config.getWaves()) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("monsterName", wave.getMonsterName());
                w.put("count", wave.getCount());
                waves.add(w);
            }
        }

        Map<String, Object> cfgMap = new LinkedHashMap<>();
        cfgMap.put("maxMonsters", config != null ? config.getMaxMonsters() : 0);
        cfgMap.put("interval", config != null ? config.getInterval() : 0);
        cfgMap.put("waves", waves);

        sendJson(ctx, Map.of(
            "type", "game.mapEntered",
            "data", Map.of(
                "mapId", mapId,
                "mapName", gameMap.getName() != null ? gameMap.getName() : ("Map " + mapId),
                "mapShortName", gameMap.getShortName() != null ? gameMap.getShortName() : "",
                "player", Map.of("x", session.getX(), "z", session.getZ()),
                "spawnPoints", spawnPoints,
                "config", cfgMap)));
    }

    private void sendJson(ChannelHandlerContext ctx, Map<String, Object> json) {
        try {
            ctx.writeAndFlush(new TextWebSocketFrame(MAPPER.writeValueAsString(json)));
        } catch (Exception e) {
            log.error("Failed to serialize JSON", e);
        }
    }

    private void sendError(ChannelHandlerContext ctx, int code, String message) {
        sendJson(ctx, Map.of(
            "type", "error",
            "data", Map.of("code", code, "message", message)));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket exception: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}