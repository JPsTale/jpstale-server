package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    // 网格边长（world 单位）：应远大于最大移动步长（RUN 高档 ~10.5/tick），
    // 否则每 1~2 帧换格触发全套 AOI 更新（push 风暴）。
    // 取视野 1086 的 ~1/8，一格容纳十几步移动。
    private static final int GRID_SIZE = 128;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private PlayerService playerService;

    /**
     * 构建完整的外观快照 Appear（Phase 3：class/level/position/hp 全量下发，客户端据此渲染）。
     */
    private MessageProto.S2C_PlayerAppear buildAppear(PlayerSession session) {
        MessageProto.S2C_PlayerAppear.Builder b = MessageProto.S2C_PlayerAppear.newBuilder()
            .setPlayerId(session.getCharacterId())
            .setName(session.getCharacterName() != null ? session.getCharacterName() : "")
            .setLevel(session.getLevel())
            .setHp(session.getHp())
            .setMaxHp(session.getMaxHp())
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) session.getX())
                .setY((float) session.getY())
                .setZ((float) session.getZ())
                .build());
        Player p = playerService.getPlayer(session);
        if (p != null) {
            b.setClassId(p.getJob());
            if (p.getAppearance() != null) {
                b.setAppearance(p.getAppearance());
            }
        }
        return b.build();
    }

    // X坐标 → 玩家ID → 玩家会话
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> xMap 
        = new ConcurrentSkipListMap<>();

    // Y坐标 → 玩家ID → 玩家会话
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerSession>> yMap 
        = new ConcurrentSkipListMap<>();

    // 玩家ID → 当前网格坐标 [x, z]
    private final ConcurrentHashMap<Long, int[]> playerGrids = new ConcurrentHashMap<>();

    // 玩家ID(观察者) → 当前可见的其他玩家ID集合（持久化，双阈值升降的核心状态）
    // 进入 CONNECT(1086) 半径 → 加入并 Appear；超出 DISCONNECT(1810) → 移除并 Disappear。
    private final ConcurrentHashMap<Long, Set<Long>> visiblePlayers = new ConcurrentHashMap<>();

    /**
     * 添加玩家到 AOI
     */
    public void addPlayer(PlayerSession session, double x, double z) {
        int gridX = toGrid(x);
        int gridZ = toGrid(z);

        addToMap(xMap, gridX, session);
        addToMap(yMap, gridZ, session);
        playerGrids.put(session.getCharacterId(), new int[]{gridX, gridZ});
        visiblePlayers.put(session.getCharacterId(), ConcurrentHashMap.newKeySet());

        log.info("[AOI] {} (id={}) addPlayer grid=({},{}) pos=({},{})",
            session.getCharacterName(), session.getCharacterId(), gridX, gridZ,
            (float) x, (float) z);
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

        // 清理可见集合：观察者自身的 + 其余观察者集合中引用本玩家的条目
        visiblePlayers.remove(playerId);
        for (Set<Long> set : visiblePlayers.values()) {
            set.remove(playerId);
        }

        log.info("[AOI] {} (id={}) removePlayer grid=({},{})",
            session.getCharacterName(), playerId,
            grids != null ? grids[0] : -1, grids != null ? grids[1] : -1);
    }

    /**
     * 玩家移动时更新 AOI
     */
    public void onPlayerMove(PlayerSession session, double newX, double newZ) {
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

        // 移动到新位置
        addToMap(xMap, newGridX, session);
        addToMap(yMap, newGridZ, session);
        playerGrids.put(playerId, new int[]{newGridX, newGridZ});

        log.info("[AOI] {} (id={}) grid {}->{} pos=({},{})",
            session.getCharacterName(), playerId, oldGridX, newGridX,
            (float) newX, (float) newZ);

        // 检查进出视野的实体
        checkVisibility(session, oldGridX, oldGridZ, newGridX, newGridZ);
    }

    /**
     * 获取视野内的所有玩家
     */
    public Set<PlayerSession> getNearbyPlayers(double x, double z) {
        return getNearbyPlayers(x, z, VIEW_RANGE);
    }

    /**
     * 获取指定范围内的所有玩家
     * 分区候选 → 精确距离过滤（利用 session 最新 x/z）
     */
    public Set<PlayerSession> getNearbyPlayers(double x, double z, float range) {
        int gridX = toGrid(x);
        int gridZ = toGrid(z);
        int gridRange = (int) (range / GRID_SIZE) + 1;

        Set<PlayerSession> xPlayers = getPlayersInRange(xMap, gridX, gridRange);
        Set<PlayerSession> yPlayers = getPlayersInRange(yMap, gridZ, gridRange);

        // 取交集
        xPlayers.retainAll(yPlayers);

        // 精确距离过滤（session 坐标由调用方先 setX/setZ 保证最新）
        float rangeSq = range * range;
        Set<PlayerSession> result = new HashSet<>(xPlayers.size());
        for (PlayerSession session : xPlayers) {
            double dx = session.getX() - x;
            double dz = session.getZ() - z;
            if (dx * dx + dz * dz <= rangeSq) {
                result.add(session);
            }
        }

        return result;
    }

    /**
     * 玩家进入（进游戏 / 切换地图）后的一次性视野广播：
     * 向新玩家下发视野内现有玩家 Appear，同时向现有玩家下发新玩家 Appear。
     * 坐标系统一为世界坐标，天然支持跨图互见。
     */
    public void onPlayerEnter(PlayerSession session) {
        Long playerId = session.getCharacterId();
        if (playerId == null) return;

        MessageProto.S2C_PlayerAppear selfAppear = buildAppear(session);
        Set<Long> visible = visiblePlayers.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        visible.clear();
        StringBuilder appearLog = new StringBuilder();
        for (PlayerSession nearby : getNearbyPlayers(session.getX(), session.getZ())) {
            if (nearby.getCharacterId() == null || nearby.getCharacterId().equals(playerId)) continue;
            // 新玩家：附近已有玩家的外观快照（此前漏发——新玩家视野是空的）
            session.send(MessageProto.ServerMessage.newBuilder().setPlayerAppear(buildAppear(nearby)).build());
            // 附近玩家：新玩家的外观快照
            nearby.send(MessageProto.ServerMessage.newBuilder().setPlayerAppear(selfAppear).build());
            // 双方可见集合同步播种，避免首次跨格移动时重复 Appear
            visible.add(nearby.getCharacterId());
            visiblePlayers.computeIfAbsent(nearby.getCharacterId(), k -> ConcurrentHashMap.newKeySet())
                .add(playerId);
            appearLog.append(nearby.getCharacterId()).append(",");
        }
        log.info("[AOI] {} (id={}) onPlayerEnter pos=({},{}) nearby=[{}]",
            session.getCharacterName(), playerId,
            (float) session.getX(), (float) session.getZ(), appearLog);
    }

    /**
     * 玩家离开（登出/断线/回选角）时向视野内玩家广播 Disappear。
     * 注意：切图（switchMap）不应调用 —— 无缝世界坐标不变，旧图邻居仍相邻可见。
     */
    public void onPlayerLeave(PlayerSession session) {
        Long playerId = session.getCharacterId();
        if (playerId == null) return;

        MessageProto.S2C_PlayerDisappear msg = MessageProto.S2C_PlayerDisappear.newBuilder()
            .setPlayerId(playerId)
            .build();
        for (PlayerSession nearby : getNearbyPlayers(session.getX(), session.getZ(), VIEW_RANGE_DISCONNECT)) {
            if (nearby.getCharacterId() == null || nearby.getCharacterId().equals(playerId)) continue;
            nearby.send(MessageProto.ServerMessage.newBuilder().setPlayerDisappear(msg).build());
        }
        // 清理可见集合（与 removePlayer 一致）
        visiblePlayers.remove(playerId);
        for (Set<Long> set : visiblePlayers.values()) {
            set.remove(playerId);
        }
        log.info("[AOI] {} (id={}) onPlayerLeave pos=({},{})",
            session.getCharacterName(), playerId,
            (float) session.getX(), (float) session.getZ());
    }

    /**
     * 检查进出视野的实体
     * 双阈值（EU 语义）：进入 CONNECT(1086) 半径 → Appear；超出 DISCONNECT(1810) → Disappear。
     * 可见性基于持久化的 visiblePlayers 集合（而非跨格时从旧格中心重建）：
     * 全程停留在 [1086, 1810] 环带内的玩家不会反复 Disappear→Appear，也不会在重进 1086 时漏发 Appear。
     */
    private void checkVisibility(PlayerSession movedPlayer,
                               int oldGridX, int oldGridZ,
                               int newGridX, int newGridZ) {
        Long moverId = movedPlayer.getCharacterId();
        if (moverId == null) return;

        double mx = movedPlayer.getX();
        double mz = movedPlayer.getZ();
        Set<Long> visible = visiblePlayers.computeIfAbsent(moverId, k -> ConcurrentHashMap.newKeySet());

        // 1) 新进入 CONNECT 半径的玩家 → 双向 Appear（并同步双方可见集合，防重复）
        StringBuilder appearLog = new StringBuilder();
        for (PlayerSession session : getNearbyPlayers(mx, mz, VIEW_RANGE)) {
            Long otherId = session.getCharacterId();
            if (otherId == null || otherId.equals(moverId)) continue;
            if (visible.add(otherId)) {
                // 通知移动玩家：新玩家出现（全量快照）
                movedPlayer.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerAppear(buildAppear(session))
                    .build());
                // 通知新玩家：移动玩家出现（全量快照）
                session.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerAppear(buildAppear(movedPlayer))
                    .build());
                visiblePlayers.computeIfAbsent(otherId, k -> ConcurrentHashMap.newKeySet()).add(moverId);
                appearLog.append(otherId).append(",");
            }
        }

        // 2) 移出 DISCONNECT 扫描半径的玩家 → 双向 Disappear（并清理双方可见集合）
        StringBuilder disappearLog = new StringBuilder();
        Set<Long> candidates = new HashSet<>();
        for (PlayerSession session : getNearbyPlayers(mx, mz, VIEW_RANGE_DISCONNECT)) {
            Long otherId = session.getCharacterId();
            if (otherId != null && !otherId.equals(moverId)) candidates.add(otherId);
        }
        List<Long> toRemove = new ArrayList<>();
        for (Long otherId : visible) {
            if (!candidates.contains(otherId)) {
                toRemove.add(otherId);
                // 通知移动玩家：玩家离开
                movedPlayer.send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerDisappear(MessageProto.S2C_PlayerDisappear.newBuilder()
                        .setPlayerId(otherId)
                        .build())
                    .build());
                // 通知离开玩家：移动玩家离开
                PlayerSession otherSession = sessionManager.getSessionByCharacterId(otherId);
                if (otherSession != null) {
                    otherSession.send(MessageProto.ServerMessage.newBuilder()
                        .setPlayerDisappear(MessageProto.S2C_PlayerDisappear.newBuilder()
                            .setPlayerId(moverId)
                            .build())
                        .build());
                    Set<Long> otherVisible = visiblePlayers.get(otherId);
                    if (otherVisible != null) otherVisible.remove(moverId);
                }
                disappearLog.append(otherId).append(",");
            }
        }
        visible.removeAll(toRemove);

        if (appearLog.length() > 0 || disappearLog.length() > 0) {
            log.info("[AOI] {} (id={}) grid {}->{} appear=[{}] disappear=[{}] pos=({},{})",
                movedPlayer.getCharacterName(), moverId,
                oldGridX, newGridX, appearLog, disappearLog, (float) mx, (float) mz);
        }
    }

    private int toGrid(double coord) {
        // floorDiv：负数坐标（地图 center 有负值）下格子边界对称，避免 truncate 跨 0 轴不对称
        return Math.floorDiv((int) coord, GRID_SIZE);
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
