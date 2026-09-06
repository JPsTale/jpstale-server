package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerSession;
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
 * AOI (Area of Interest) 管理器 —— M4:以 PlayerEntity 为成员(D1)。
 *
 * 网格里存放的是玩家运行时实体(坐标/属性权威),不再是 PlayerSession;
 * 发送消息仍经 e.getSession()。为未来"怪/地面物进同一全局索引"铺路。
 *
 * 双阈值(EU):进入 CONNECT(1086) 半径 → Appear;超出 DISCONNECT(1810) → Disappear。
 * 身份键 = PlayerEntity.getId()(运行时全局唯一 id)。
 */
@Slf4j
@Component
public class AOIManager {

    // EU 原始实现：CONNECT≈1086 / DISCONNECT≈1810
    public static final float VIEW_RANGE = 1086f;
    public static final float VIEW_RANGE_DISCONNECT = 1810f;
    private static final int GRID_SIZE = 128;

    // X坐标 → 实体运行时id → 玩家实体
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> xMap
        = new ConcurrentSkipListMap<>();
    // Z坐标 → 实体运行时id → 玩家实体
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> yMap
        = new ConcurrentSkipListMap<>();

    // 实体运行时id → 当前网格 [x, z]
    private final ConcurrentHashMap<Long, int[]> playerGrids = new ConcurrentHashMap<>();

    // 观察者实体id → 当前可见的其他玩家实体id集合(持久化,双阈值)
    private final ConcurrentHashMap<Long, Set<Long>> visiblePlayers = new ConcurrentHashMap<>();

    private static PlayerSession sessionOf(PlayerEntity e) {
        return e != null ? e.getSession() : null;
    }

    private static String nameOf(PlayerEntity e) {
        PlayerSession s = sessionOf(e);
        return s != null && s.getCharacterName() != null ? s.getCharacterName() : "?";
    }

    /**
     * 构建完整的外观快照 Appear(属性/坐标读 PlayerEntity)。
     */
    private MessageProto.S2C_PlayerAppear buildAppear(PlayerEntity e) {
        PlayerSession session = sessionOf(e);
        Player p = e.getPlayer();
        MessageProto.S2C_PlayerAppear.Builder b = MessageProto.S2C_PlayerAppear.newBuilder()
            .setPlayerId(session != null ? session.getCharacterId() : e.getId())
            .setName(session != null && session.getCharacterName() != null ? session.getCharacterName() : "")
            .setLevel(e.getLevel())
            .setHp(e.getHp())
            .setMaxHp(e.getMaxHp())
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) e.getX())
                .setY((float) e.getY())
                .setZ((float) e.getZ())
                .build())
            .setAngle((float) e.getAngle());
        if (p != null) {
            b.setClassId(p.getJob());
            if (p.getAppearance() != null) {
                b.setAppearance(p.getAppearance());
            }
        }
        return b.build();
    }

    /**
     * 添加玩家实体到 AOI
     */
    public void addPlayer(PlayerEntity entity) {
        if (entity == null) return;
        Long eid = entity.getId();
        int gridX = toGrid(entity.getX());
        int gridZ = toGrid(entity.getZ());
        addToMap(xMap, gridX, entity);
        addToMap(yMap, gridZ, entity);
        playerGrids.put(eid, new int[]{gridX, gridZ});
        visiblePlayers.put(eid, ConcurrentHashMap.newKeySet());
        log.info("[AOI] {} (id={}) addPlayer grid=({},{}) pos=({},{})",
            nameOf(entity), eid, gridX, gridZ, (float) entity.getX(), (float) entity.getZ());
    }

    /**
     * 从 AOI 移除玩家实体
     */
    public void removePlayer(PlayerEntity entity) {
        if (entity == null) return;
        Long eid = entity.getId();
        int[] grids = playerGrids.remove(eid);
        if (grids != null) {
            removeFromMap(xMap, grids[0], entity);
            removeFromMap(yMap, grids[1], entity);
        }
        visiblePlayers.remove(eid);
        for (Set<Long> set : visiblePlayers.values()) {
            set.remove(eid);
        }
        log.info("[AOI] {} (id={}) removePlayer grid=({},{})",
            nameOf(entity), eid, grids != null ? grids[0] : -1, grids != null ? grids[1] : -1);
    }

    /**
     * 玩家实体移动(坐标已在实体上更新)时刷新 AOI
     */
    public void onPlayerMove(PlayerEntity entity) {
        if (entity == null) return;
        Long eid = entity.getId();
        int[] oldGrids = playerGrids.get(eid);
        if (oldGrids == null) return;

        int oldGridX = oldGrids[0];
        int oldGridZ = oldGrids[1];
        int newGridX = toGrid(entity.getX());
        int newGridZ = toGrid(entity.getZ());

        if (oldGridX == newGridX && oldGridZ == newGridZ) return;

        removeFromMap(xMap, oldGridX, entity);
        removeFromMap(yMap, oldGridZ, entity);
        addToMap(xMap, newGridX, entity);
        addToMap(yMap, newGridZ, entity);
        playerGrids.put(eid, new int[]{newGridX, newGridZ});

        log.info("[AOI] {} (id={}) grid {}->{} pos=({},{})",
            nameOf(entity), eid, oldGridX, newGridX, (float) entity.getX(), (float) entity.getZ());

        checkVisibility(entity, oldGridX, oldGridZ, newGridX, newGridZ);
    }

    public Set<PlayerEntity> getNearbyPlayers(double x, double z) {
        return getNearbyPlayers(x, z, VIEW_RANGE);
    }

    public Set<PlayerEntity> getNearbyPlayers(double x, double z, float range) {
        int gridX = toGrid(x);
        int gridZ = toGrid(z);
        int gridRange = (int) (range / GRID_SIZE) + 1;

        Set<PlayerEntity> xSet = getInRange(xMap, gridX, gridRange);
        Set<PlayerEntity> zSet = getInRange(yMap, gridZ, gridRange);
        xSet.retainAll(zSet);

        float rangeSq = range * range;
        Set<PlayerEntity> result = new HashSet<>(xSet.size());
        for (PlayerEntity e : xSet) {
            double dx = e.getX() - x;
            double dz = e.getZ() - z;
            if (dx * dx + dz * dz <= rangeSq) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * 玩家实体进场:向自己下发视野内现有玩家 Appear,向现有玩家下发自己 Appear。
     */
    public void onPlayerEnter(PlayerEntity entity) {
        if (entity == null) return;
        Long eid = entity.getId();
        PlayerSession session = sessionOf(entity);

        MessageProto.S2C_PlayerAppear selfAppear = buildAppear(entity);
        Set<Long> visible = visiblePlayers.computeIfAbsent(eid, k -> ConcurrentHashMap.newKeySet());
        visible.clear();
        StringBuilder appearLog = new StringBuilder();
        for (PlayerEntity nearby : getNearbyPlayers(entity.getX(), entity.getZ())) {
            if (nearby.getId() == eid) continue;
            // 新玩家:附近已有玩家的外观快照
            session.send(MessageProto.ServerMessage.newBuilder().setPlayerAppear(buildAppear(nearby)).build());
            // 附近玩家:新玩家的外观快照
            sessionOf(nearby).send(MessageProto.ServerMessage.newBuilder().setPlayerAppear(selfAppear).build());
            visible.add(nearby.getId());
            visiblePlayers.computeIfAbsent(nearby.getId(), k -> ConcurrentHashMap.newKeySet()).add(eid);
            appearLog.append(nameOf(nearby)).append(",");
        }
        log.info("[AOI] {} (id={}) onPlayerEnter pos=({},{}) nearby=[{}]",
            nameOf(entity), eid, (float) entity.getX(), (float) entity.getZ(), appearLog);
    }

    /**
     * 玩家离开(登出/断线)时向视野内玩家广播 Disappear。
     * 切图不调用(无缝世界坐标连续)。
     */
    public void onPlayerLeave(PlayerEntity entity) {
        if (entity == null) return;
        Long eid = entity.getId();
        PlayerSession session = sessionOf(entity);
        if (session == null) return;

        MessageProto.S2C_PlayerDisappear msg = MessageProto.S2C_PlayerDisappear.newBuilder()
            .setPlayerId(session.getCharacterId())
            .build();
        for (PlayerEntity nearby : getNearbyPlayers(entity.getX(), entity.getZ(), VIEW_RANGE_DISCONNECT)) {
            if (nearby.getId() == eid) continue;
            sessionOf(nearby).send(MessageProto.ServerMessage.newBuilder().setPlayerDisappear(msg).build());
        }
        visiblePlayers.remove(eid);
        for (Set<Long> set : visiblePlayers.values()) {
            set.remove(eid);
        }
        log.info("[AOI] {} (id={}) onPlayerLeave pos=({},{})",
            nameOf(entity), eid, (float) entity.getX(), (float) entity.getZ());
    }

    private void checkVisibility(PlayerEntity moved,
                                 int oldGridX, int oldGridZ,
                                 int newGridX, int newGridZ) {
        Long eid = moved.getId();
        double mx = moved.getX();
        double mz = moved.getZ();
        Set<Long> visible = visiblePlayers.computeIfAbsent(eid, k -> ConcurrentHashMap.newKeySet());

        // 1) 新进入 CONNECT 半径的玩家 → 双向 Appear
        StringBuilder appearLog = new StringBuilder();
        for (PlayerEntity other : getNearbyPlayers(mx, mz, VIEW_RANGE)) {
            Long oid = other.getId();
            if (oid == eid) continue;
            if (visible.add(oid)) {
                moved.getSession().send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerAppear(buildAppear(other)).build());
                sessionOf(other).send(MessageProto.ServerMessage.newBuilder()
                    .setPlayerAppear(buildAppear(moved)).build());
                visiblePlayers.computeIfAbsent(oid, k -> ConcurrentHashMap.newKeySet()).add(eid);
                appearLog.append(nameOf(other)).append(",");
            }
        }

        // 2) 移出 DISCONNECT 半径 → 双向 Disappear
        StringBuilder disappearLog = new StringBuilder();
        Set<Long> candidates = new HashSet<>();
        for (PlayerEntity other : getNearbyPlayers(mx, mz, VIEW_RANGE_DISCONNECT)) {
            if (other.getId() != eid) candidates.add(other.getId());
        }
        List<Long> toRemove = new ArrayList<>();
        for (Long oid : visible) {
            if (!candidates.contains(oid)) {
                toRemove.add(oid);
                PlayerSession otherSession = sessionOf(playerById(oid));
                if (otherSession != null) {
                    otherSession.send(MessageProto.ServerMessage.newBuilder()
                        .setPlayerDisappear(MessageProto.S2C_PlayerDisappear.newBuilder()
                            .setPlayerId(otherSession.getCharacterId()).build())
                        .build());
                    Set<Long> otherVisible = visiblePlayers.get(oid);
                    if (otherVisible != null) otherVisible.remove(eid);
                }
                PlayerSession self = moved.getSession();
                if (self != null) {
                    self.send(MessageProto.ServerMessage.newBuilder()
                        .setPlayerDisappear(MessageProto.S2C_PlayerDisappear.newBuilder()
                            .setPlayerId(otherSession != null ? otherSession.getCharacterId() : oid).build())
                        .build());
                }
                disappearLog.append(oid).append(",");
            }
        }
        visible.removeAll(toRemove);

        if (appearLog.length() > 0 || disappearLog.length() > 0) {
            log.info("[AOI] {} (id={}) grid {}->{} appear=[{}] disappear=[{}] pos=({},{})",
                nameOf(moved), eid, oldGridX, newGridX, appearLog, disappearLog, (float) mx, (float) mz);
        }
    }

    private PlayerEntity playerById(long id) {
        for (Map<Integer, ConcurrentHashMap<Long, PlayerEntity>> m : List.of(xMap)) {
            for (ConcurrentHashMap<Long, PlayerEntity> cell : m.values()) {
                PlayerEntity e = cell.get(id);
                if (e != null) return e;
            }
        }
        return null;
    }

    private int toGrid(double coord) {
        return Math.floorDiv((int) coord, GRID_SIZE);
    }

    private void addToMap(ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> map,
                          int grid, PlayerEntity e) {
        map.computeIfAbsent(grid, k -> new ConcurrentHashMap<>()).put(e.getId(), e);
    }

    private void removeFromMap(ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> map,
                               int grid, PlayerEntity e) {
        ConcurrentHashMap<Long, PlayerEntity> cell = map.get(grid);
        if (cell != null) {
            cell.remove(e.getId());
            if (cell.isEmpty()) map.remove(grid);
        }
    }

    private Set<PlayerEntity> getInRange(
        ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> map,
        int center, int range) {
        Set<PlayerEntity> result = new HashSet<>();
        for (ConcurrentHashMap<Long, PlayerEntity> cell : map.subMap(center - range, center + range).values()) {
            result.addAll(cell.values());
        }
        return result;
    }
}
