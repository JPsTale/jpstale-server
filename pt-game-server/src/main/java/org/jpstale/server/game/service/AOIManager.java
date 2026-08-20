package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * AOI (Area of Interest) 管理器
 * 使用十字链表实现，两个 ConcurrentSkipListMap 分别存储 X/Y 坐标到玩家的映射
 */
@Slf4j
@Component
public class AOIManager {

    // EU 原始实现（exm/smType.h + character.h）：
    // DIST_TRANSLEVEL_CONNECT = 0x120000（平方距离，定点数已除 FLOATNS=8）→ √ ≈ 1086 单位
    // DIST_TRANSLEVEL_DISCONNECT = 0x320000 → √ ≈ 1810 单位
    // 出现/消失用双阈值避免抖动；服务端不裁剪，视野内全部可见。
    public static final float VIEW_RANGE = 1086f;
    public static final float VIEW_RANGE_DISCONNECT = 1810f;
    private static final int GRID_SIZE = 10;

    @Autowired
    private SessionManager sessionManager;

    // X坐标 → 玩家ID → 玩家会话
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> xMap 
        = new ConcurrentSkipListMap<>();

    // Y坐标 → 玩家ID → 玩家会话
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> yMap 
        = new ConcurrentSkipListMap<>();

    // 玩家ID → 当前网格坐标 [x, z]
    private final ConcurrentHashMap<Long, int[]> playerGrids = new ConcurrentHashMap<>();

    /**
     * 添加玩家到 AOI
     */
    public void addPlayer(PlayerSession session, float x, float z) {
        int gridX = toGrid(x);
        int gridZ = toGrid(z);

        addToMap(xMap, gridX, session);
        addToMap(yMap, gridZ, session);
        playerGrids.put(session.getCharacterId(), new int[]{gridX, gridZ});

        log.debug("Player {} added to AOI at grid ({}, {})", session.getCharacterName(), gridX, gridZ);
    }

    /**
     * 从 AOI 移除玩家
     */
    public void removePlayer(PlayerSession session) {
        Long playerId = session.getCharacterId();
        if (playerId == null) return;

        int[] grids = playerGrids.remove(playerId);
        if (grids != null) {
            removeFromMap(xMap, grids[0], session);
            removeFromMap(yMap, grids[1], session);
        }

        log.debug("Player {} removed from AOI", session.getCharacterName());
    }

    /**
     * 玩家移动时更新 AOI
     */
    public void onPlayerMove(PlayerSession session, float newX, float newZ) {
        Long playerId = session.getCharacterId();
        if (playerId == null) return;

        int[] oldGrids = playerGrids.get(playerId);
        if (oldGrids == null) return;

        int oldGridX = oldGrids[0];
        int oldGridZ = oldGrids[1];
        int newGridX = toGrid(newX);
        int newGridZ = toGrid(newZ);

        // 坐标没变，不需要更新
        if (oldGridX == newGridX && oldGridZ == newGridZ) return;

        // 从旧位置移除
        removeFromMap(xMap, oldGridX, session);
        removeFromMap(yMap, oldGridZ, session);

        // 添加到新位置
        addToMap(xMap, newGridX, session);
        addToMap(yMap, newGridZ, session);
        playerGrids.put(playerId, new int[]{newGridX, newGridZ});

        // 检查进出视野的实体
        checkVisibility(session, oldGridX, oldGridZ, newGridX, newGridZ);
    }

    /**
     * 获取视野内的所有玩家
     */
    public Set<PlayerSession> getNearbyPlayers(float x, float z) {
        return getNearbyPlayers(x, z, VIEW_RANGE);
    }

    /**
     * 获取指定范围内的所有玩家
     */
    public Set<PlayerSession> getNearbyPlayers(float x, float z, float range) {
        int gridX = toGrid(x);
        int gridZ = toGrid(z);
        int gridRange = (int) (range / GRID_SIZE) + 1;

        Set<PlayerSession> xPlayers = getPlayersInRange(xMap, gridX, gridRange);
        Set<PlayerSession> yPlayers = getPlayersInRange(yMap, gridZ, gridRange);

        // 取交集
        xPlayers.retainAll(yPlayers);

        // 精确距离过滤
        Set<PlayerSession> result = new HashSet<>();
        for (PlayerSession session : xPlayers) {
            // TODO: 获取玩家实际位置进行精确过滤
            result.add(session);
        }

        return result;
    }

    /**
     * 检查进出视野的实体
     */
    private void checkVisibility(PlayerSession movedPlayer, 
                               int oldGridX, int oldGridZ, 
                               int newGridX, int newGridZ) {
        // 旧视野内的玩家
        Set<PlayerSession> oldVisible = getNearbyPlayers(oldGridX * GRID_SIZE, oldGridZ * GRID_SIZE);
        // 新视野内的玩家
        Set<PlayerSession> newVisible = getNearbyPlayers(newGridX * GRID_SIZE, newGridZ * GRID_SIZE);

        // 新进入视野的玩家 → 发送 Appear
        for (PlayerSession session : newVisible) {
            if (!oldVisible.contains(session) && session.getCharacterId() != movedPlayer.getCharacterId()) {
                // 通知移动玩家：新玩家出现
                movedPlayer.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerAppear(MessageProto.S2C_PlayerAppear.newBuilder()
                        .setPlayerId(session.getCharacterId())
                        .setName(session.getCharacterName() != null ? session.getCharacterName() : "")
                        .build())
                    .build());

                // 通知新玩家：移动玩家出现
                session.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerAppear(MessageProto.S2C_PlayerAppear.newBuilder()
                        .setPlayerId(movedPlayer.getCharacterId())
                        .setName(movedPlayer.getCharacterName() != null ? movedPlayer.getCharacterName() : "")
                        .build())
                    .build());
            }
        }

        // 离开视野的玩家 → 发送 Disappear
        for (PlayerSession session : oldVisible) {
            if (!newVisible.contains(session) && session.getCharacterId() != movedPlayer.getCharacterId()) {
                // 通知移动玩家：玩家离开
                movedPlayer.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerDisappear(MessageProto.S2C_PlayerDisappear.newBuilder()
                        .setPlayerId(session.getCharacterId())
                        .build())
                    .build());

                // 通知离开玩家：移动玩家离开
                session.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerDisappear(MessageProto.S2C_PlayerDisappear.newBuilder()
                        .setPlayerId(movedPlayer.getCharacterId())
                        .build())
                    .build());
            }
        }
    }

    private int toGrid(float coord) {
        return (int) (coord / GRID_SIZE);
    }

    private void addToMap(ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> map, 
                         int grid, PlayerSession session) {
        Long playerId = session.getCharacterId();
        if (playerId != null) {
            map.computeIfAbsent(grid, k -> new ConcurrentHashMap<>()).put(playerId, session);
        }
    }

    private void removeFromMap(ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> map, 
                              int grid, PlayerSession session) {
        Long playerId = session.getCharacterId();
        if (playerId != null) {
            ConcurrentHashMap<Long, PlayerSession> players = map.get(grid);
            if (players != null) {
                players.remove(playerId);
                if (players.isEmpty()) {
                    map.remove(grid);
                }
            }
        }
    }

    private Set<PlayerSession> getPlayersInRange(
            ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> map,
            int center, int range) {
        Set<PlayerSession> result = new HashSet<>();
        for (ConcurrentHashMap<Long, PlayerSession> players : 
             map.subMap(center - range, center + range).values()) {
            result.addAll(players.values());
        }
        return result;
    }
}
