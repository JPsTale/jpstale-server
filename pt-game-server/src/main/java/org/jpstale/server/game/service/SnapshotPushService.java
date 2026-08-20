package org.jpstale.server.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.GameMap;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.SpawnPoint;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 刷怪调试快照推送。
 * <p>
 * 每 500ms 向所有 WebSocket 调试客户端推送 game.snapshot，
 * 包含：玩家位置、该地图所有怪物（位置/状态/血量）、刷怪点状态（active/计数/上限）。
 */
@Slf4j
@Component
public class SnapshotPushService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MapManager mapManager;

    @Autowired
    private MonsterSpawnService monsterSpawnService;

    @Autowired
    private AOIManager aoiManager;

    // 每个观察者当前可见的其他玩家 id（EU 双阈值滞后：出现用 CONNECT，消失用 DISCONNECT）
    private final Map<Long, Set<Long>> visiblePlayers = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 500)
    public void pushSnapshots() {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (!session.isPlaying() || session.getCurrentMapId() < 0) {
                continue;
            }
            if (session.getChannel() == null
                || session.getChannel().pipeline().get(
                    io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.class) == null) {
                // 只推给 WebSocket 调试客户端，跳过二进制 TCP 客户端
                continue;
            }

            int mapId = session.getCurrentMapId();
            GameMap gameMap = mapManager.getMap(mapId);
            if (gameMap == null) {
                continue;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "game.snapshot");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tick", System.currentTimeMillis());
            data.put("mapId", mapId);
            data.put("player", Map.of("x", session.getX(), "z", session.getZ(),
                "hp", session.getHp(), "maxHp", session.getMaxHp(),
                "mp", session.getMp(), "maxMp", session.getMaxMp(),
                "level", session.getLevel()));

            // 怪物列表
            List<Map<String, Object>> monsters = new ArrayList<>();
            for (Monster m : monsterSpawnService.getMonstersByMap(mapId)) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", m.getId());
                mm.put("templateId", m.getTemplateId());
                mm.put("name", m.getName());
                mm.put("level", m.getLevel());
                mm.put("x", m.getX());
                mm.put("z", m.getZ());
                mm.put("hp", m.getHp());
                mm.put("maxHp", m.getMaxHp());
                mm.put("state", m.getState().name());
                mm.put("targetPlayerId", m.getTargetPlayerId());
                monsters.add(mm);
            }
            data.put("monsters", monsters);

            // 附近其他玩家（AOI，EU 双阈值：出现距离 CONNECT≈1086，消失距离 DISCONNECT≈1810）
            List<Map<String, Object>> players = new ArrayList<>();
            Set<Long> observerVisible = visiblePlayers.computeIfAbsent(
                session.getCharacterId(), k -> ConcurrentHashMap.newKeySet());
            Set<Long> stillVisible = new HashSet<>();
            for (PlayerSession other : aoiManager.getNearbyPlayers(session.getX(), session.getZ(), AOIManager.VIEW_RANGE_DISCONNECT)) {
                if (other.getCharacterId() == null
                    || other.getCharacterId().equals(session.getCharacterId())
                    || !other.isPlaying()
                    || other.getCurrentMapId() != mapId) {
                    continue;
                }
                float dx = other.getX() - session.getX();
                float dz = other.getZ() - session.getZ();
                float distSq = dx * dx + dz * dz;
                boolean wasVisible = observerVisible.contains(other.getCharacterId());
                if (distSq > AOIManager.VIEW_RANGE_DISCONNECT * AOIManager.VIEW_RANGE_DISCONNECT) {
                    continue;
                }
                // 未出现过：需要进入出现距离（CONNECT）；已出现过：保持到消失距离（DISCONNECT）
                if (!wasVisible && distSq > AOIManager.VIEW_RANGE * AOIManager.VIEW_RANGE) {
                    continue;
                }
                stillVisible.add(other.getCharacterId());
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", other.getCharacterId());
                pm.put("name", other.getCharacterName() != null ? other.getCharacterName() : "");
                pm.put("x", other.getX());
                pm.put("z", other.getZ());
                pm.put("level", other.getLevel());
                pm.put("hp", other.getHp());
                players.add(pm);
            }
            observerVisible.retainAll(stillVisible);
            data.put("players", players);

            // 刷怪点状态
            List<Map<String, Object>> points = new ArrayList<>();
            for (SpawnPoint sp : gameMap.getSpawnPoints()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", sp.getId());
                pm.put("x", sp.getX());
                pm.put("z", sp.getZ());
                pm.put("active", sp.isActive());
                pm.put("monsterCount", sp.getMonsterCount().get());
                pm.put("maxMonsters", sp.getMaxMonsters());
                points.add(pm);
            }
            data.put("spawnPoints", points);

            out.put("data", data);

            try {
                session.sendText(MAPPER.writeValueAsString(out));
            } catch (Exception e) {
                log.error("Failed to serialize snapshot for player {}", session.getCharacterName(), e);
            }
        }
    }
}