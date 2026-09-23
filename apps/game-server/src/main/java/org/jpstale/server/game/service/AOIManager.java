package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.dao.clandb.mapper.UlMapper;
import org.jpstale.server.common.model.CharacterAppearance;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.network.AppearanceCodec;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * AOI (Area of Interest) 管理器 —— M4:以 PlayerEntity 为成员(D1)。
 *
 * 网格里存放的是玩家运行时实体(坐标/属性权威),不再是 PlayerSession;
 * 发送消息仍经 e.getSession()。为未来"怪/地面物进同一全局索引"铺路。
 *
 * AOI 距离 = 1000 进入 / 1600 离开（用户 2026-09-14 定；原本是 EU 的 1086/1810）。
 * 玩家/怪物/NPC/地面物品四类共用下面两个常量，所以"统一"只需改这里。
 * 身份键 = PlayerEntity.getId()(运行时全局唯一 id)。
 */
@Slf4j
@Component
public class AOIManager {

    /**
     * AOI 距离：进入 {@link #VIEW_RANGE}(1000) → Appear；超出 {@link #VIEW_RANGE_DISCONNECT}(1600) → Disappear。
     *
     * **两个数必须不等，差值就是滞回区**（用户 2026-09-14 定 1000/1600）：实体在 1000~1600
     * 这圈里来回走**不会**反复 Appear/Disappear。这很关键 —— 客户端每次 Appear 都要重新构建
     * 模型与贴图，边界抖动比多显示几只怪贵得多。1600 相对 1000 留了 60% 余量
     * （原 EU 口径 1086/1810 ≈ 67%，同量级）。
     *
     * ⚠ 约束：所有"广播 / 清理范围"都必须 **≥ VIEW_RANGE** —— 不能小于可见边界，否则边界附近的
     * 观察者收不到位置更新、或收不到消失通知。`MovementService` 的玩家移动广播、本类
     * `onPlayerLeave` 都取 DISCONNECT，正好满足（等价于取可见集合的边界）。
     *
     * ⚠ 与怪 AI 的模拟半径（{@code AIConstants.ACTIVE_RADIUS} = 1810）**不是一回事**：
     * 这里管"发给哪些玩家看"，那里管"服务端模拟哪些怪的 AI"。两者不等**是有意的** ——
     * 怪在你视野外仍在活动，走近才出现在视野里。
     */
    public static final float VIEW_RANGE = 1000f;
    public static final float VIEW_RANGE_DISCONNECT = 1600f;
    private static final int GRID_SIZE = 128;

    // X坐标 → 实体运行时id → 玩家实体
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> xMap
        = new ConcurrentSkipListMap<>();
    // Z坐标 → 实体运行时id → 玩家实体
    private final ConcurrentSkipListMap<Integer, ConcurrentHashMap<Long, PlayerEntity>> yMap
        = new ConcurrentSkipListMap<>();

    // 实体运行时id → 当前网格 [x, z]
    private final ConcurrentHashMap<Long, int[]> playerGrids = new ConcurrentHashMap<>();

    // 实体运行时id → 玩家实体(权威注册表:读点 O(1),与 xMap/yMap 同生命周期维护,removePlayer 清理)
    private final ConcurrentHashMap<Long, PlayerEntity> entitiesById = new ConcurrentHashMap<>();

    // 观察者实体id → 当前可见的其他玩家实体id集合(持久化,双阈值)
    private final ConcurrentHashMap<Long, Set<Long>> visiblePlayers = new ConcurrentHashMap<>();

    @Autowired
    private UlMapper ulMapper;

    /**
     * 怪物 AOI —— 只为"玩家进入世界时清空其怪物可见集"而注入。
     * 标 `@Lazy` 切断 `AOIManager ↔ MonsterSpawnService` 的构造期环
     * （MonsterSpawnService 注入了 AOIManager；MonsterAOI 注入了 MonsterSpawnService）。
     */
    @Lazy
    @Autowired
    private MonsterAOI monsterAOI;

    /** NPC AOI —— 同 {@link #monsterAOI}：玩家进入世界/换图时清空其 NPC 可见集（无环，直接注入） */
    @Autowired
    private NpcAOI npcAOI;

    /** 地面物品 AOI —— 同 {@link #npcAOI}：清空其地面物品可见集（用户 2026-09-16 实测"刷新后看不到掉落物"） */
    @Autowired
    private GroundItemAOI groundItemAOI;

    /** 公会缓存：charId → [公会名, 图标id]（懒加载一次，玩家离场清缓存） */
    private final ConcurrentHashMap<Long, String[]> clanCache = new ConcurrentHashMap<>();

    /** 移动速度：Appear 要带上，旁观者据此缩放该角色的走/跑动画播放速度（见 S2C_PlayerAppear.walk_speed） */
    @Autowired
    private PlayerStatCalculator statCalculator;

    /**
     * 构建完整的外观快照 Appear(属性/坐标读 PlayerEntity)。
     */
    private S2C_PlayerAppear buildAppear(PlayerEntity e) {
        PlayerSession session = e.getSession();
        Player p = e.getPlayer();
        // 协议面身份**一律 charId**：客户端进图时从 S2C_EnterGame 拿到自己的 characterId，
        // 之后 Appear/Move/Disappear/Damage 的 playerId 全按它匹配。实体的运行时 id 只用于 AOI 世界网格
        // （见类注释）。原先这里在 session==null 时回退成运行时 id ⇒ 同一条消息带两个身份空间的 id，
        // 客户端会留下看不见也删不掉的幽灵玩家（AGENTS #59 追记）。
        S2C_PlayerAppear.Builder b = S2C_PlayerAppear.newBuilder()
            .setPlayerId(e.getCharId())
            .setName(e.getName() != null ? e.getName() : "")
            .setLevel(e.getLevel())
            .setHp(e.getHp())
            .setMaxHp(e.getMaxHp())
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) e.getX())
                .setY((float) e.getY())
                .setZ((float) e.getZ())
                .build())
            .setAngle((float) e.getAngle())
            // 此刻在播的那一条（进视野对齐用，见 proto 注释 / AGENTS #81）
            .setAnimIndex(e.getLastAnimIndex())
            .setAnimClip(e.getLastAnimClip() == null ? "" : e.getLastAnimClip());
        if (p != null) {
            b.setClassId(p.getJob());
            if (p.getAppearance() != null) {
                b.setAppearance(AppearanceCodec.toProto(p.getAppearance()));
            }
            // 走/跑**动画速率**（= 该档速度 ÷ 1档速度，服务端查表算好）：
            // 旁观者拿它缩放该角色的动画播放速度（动画按 1 档做的，加速后步频要跟上）。
            // ⚠ 只下发**速率**，不再下发速度值 —— 旁观者不需要速度（远端位置是插值的）。
            b.setAnimWalkRate((float) statCalculator.walkAnimRate(p));
            b.setAnimRunRate((float) statCalculator.runAnimRate(p));
        }
        String[] clan = clanOf(e);
        if (clan != null) {
            b.setClanName(clan[0]);
            b.setClanMark(clan[1]);
        }
        return b.build();
    }

    /**
     * 取玩家公会信息（懒加载 + 缓存，查不到返回 null）。
     * 失败静默降级为空串（名牌仅不显示公会行，不阻塞 appear）。
     */
    private String[] clanOf(PlayerEntity e) {
        long charId = e.getCharId();
        String[] cached = clanCache.get(charId);
        if (cached != null) return cached;
        PlayerSession s = e.getSession();
        String chName = s != null ? s.getCharacterName() : null;
        if (chName == null || chName.isEmpty()) return null;
        try {
            Map<String, Object> row = ulMapper.selectClanByChName(chName);
            String[] clan;
            if (row == null || row.get("clan_name") == null) {
                clan = new String[]{"", ""};
            } else {
                Number icon = row.get("icon_id") instanceof Number n ? n : null;
                clan = new String[]{String.valueOf(row.get("clan_name")), icon != null ? String.valueOf(icon.intValue()) : ""};
            }
            clanCache.put(charId, clan);
            return clan;
        } catch (Exception ex) {
            log.warn("[AOI] 查询公会失败 chName={}: {}", chName, ex.getMessage());
            return new String[]{"", ""};
        }
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
        entitiesById.put(eid, entity);
        visiblePlayers.put(eid, ConcurrentHashMap.newKeySet());
        log.info("[AOI] {} (id={}) addPlayer grid=({},{}) pos=({},{})",
            entity.getName(), eid, gridX, gridZ, (float) entity.getX(), (float) entity.getZ());
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
        entitiesById.remove(eid);
        visiblePlayers.remove(eid);
        clanCache.remove(entity.getCharId());
        for (Set<Long> set : visiblePlayers.values()) {
            set.remove(eid);
        }
        log.info("[AOI] {} (id={}) removePlayer grid=({},{})",
            entity.getName(), eid, grids != null ? grids[0] : -1, grids != null ? grids[1] : -1);
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
        PlayerSession session = entity.getSession();

        S2C_PlayerAppear selfAppear = buildAppear(entity);
        Set<Long> visible = visiblePlayers.computeIfAbsent(eid, k -> ConcurrentHashMap.newKeySet());
        visible.clear();
        // 怪物 AOI 的可见集同样要清（用户 2026-09-14 实测：重连后看不到怪、却一直挨打）。
        // 它曾漏了这一句 —— 玩家 AOI 清、怪物 AOI 不清，而两者都跨会话保留，
        // 于是重连时 `visible.add(mid)` 恒 false ⇒ 一条 Appear 都不发。
        // ⚠ 键是 **charId**（`MonsterAOI.syncSessions` 用 `session.getCharacterId()`），
        //   而本方法的 `eid` 是实体运行时 id —— 两者不同（日志实测：char=78 / runtimeId=151）。
        //   传错键会静默清不到任何东西，所以这里显式取 charId。
        Long charId = session != null ? session.getCharacterId() : null;
        if (charId != null) {
            monsterAOI.clearVisible(charId);
        }
        // NPC AOI 同样跨会话保留，也曾漏了清空（用户 2026-09-15：刚进图不推 NPC Appear）。
        // 它按 session 清（换图时顺带补发旧图 NPC 的 Disappear —— 客户端传送不清场）。
        npcAOI.clearVisible(session);
        // 地面物品 AOI 同理（用户 2026-09-16 实测：刷新页面后看不到地上的掉落物）。
        // 三类 AOI（怪/NPC/掉落）都是"charId 为键、跨会话保留" ⇒ **必须一起清**；
        // 漏掉任何一个，症状都是"重进后那一类实体一条 Appear 都不发"。
        groundItemAOI.clearVisible(session);
        StringBuilder appearLog = new StringBuilder();
        for (PlayerEntity nearby : getNearbyPlayers(entity.getX(), entity.getZ())) {
            if (nearby.getId() == eid) continue;
            // 新玩家:附近已有玩家的外观快照
            session.send(ServerMessage.newBuilder().setPlayerAppear(buildAppear(nearby)).build());
            // 附近玩家:新玩家的外观快照
            nearby.getSession().send(ServerMessage.newBuilder().setPlayerAppear(selfAppear).build());
            visible.add(nearby.getId());
            visiblePlayers.computeIfAbsent(nearby.getId(), k -> ConcurrentHashMap.newKeySet()).add(eid);
            appearLog.append(nearby.getName()).append(",");
        }
        log.info("[AOI] {} (id={}) onPlayerEnter pos=({},{}) nearby=[{}]",
            entity.getName(), eid, (float) entity.getX(), (float) entity.getZ(), appearLog);
    }

    /**
     * 玩家离开(登出/断线)时向视野内玩家广播 Disappear。
     * 切图不调用(无缝世界坐标连续)。
     */
    public void onPlayerLeave(PlayerEntity entity) {
        if (entity == null) return;
        Long eid = entity.getId();
        // 协议面 id 用 **charId**：它是 final 的、永远拿得到，而 session 可能已被摘除。
        // 原先这里 `session == null` 直接 return ⇒ "玩家离开但视野内客户端不知道"，那具模型会永远站着
        // （或更糟：下面别的地方退回运行时 id，客户端按 charId 匹配不上，连 Disappear 都删不掉它）。
        S2C_PlayerDisappear msg = S2C_PlayerDisappear.newBuilder()
            .setPlayerId(entity.getCharId())
            .build();
        for (PlayerEntity nearby : getNearbyPlayers(entity.getX(), entity.getZ(), VIEW_RANGE_DISCONNECT)) {
            if (nearby.getId() == eid) continue;
            PlayerSession ns = nearby.getSession();
            if (ns != null) {
                ns.send(ServerMessage.newBuilder().setPlayerDisappear(msg).build());
            }
        }
        visiblePlayers.remove(eid);
        for (Set<Long> set : visiblePlayers.values()) {
            set.remove(eid);
        }
        log.info("[AOI] {} (id={}) onPlayerLeave pos=({},{})",
            entity.getName(), eid, (float) entity.getX(), (float) entity.getZ());
    }

    /**
     * 外观更新广播（穿脱装备/武器切换后调用）：
     * 自机收自己一份；视野内其他玩家各收一份。
     * @param entity 外观变化的玩家实体
     * @param appearance 新外观（调用方已 recalc 并缓存到 Player）
     */
    public void broadcastAppearance(PlayerEntity entity, CharacterAppearance appearance) {
        if (entity == null) {
            return;
        }
        PlayerSession session = entity.getSession();
        long pid = entity.getCharId();   // 协议面 = charId（唯一身份空间，见 buildAppear 注释）
        S2C_AppearanceUpdate msg = S2C_AppearanceUpdate.newBuilder()
            .setPlayerId(pid)
            .setAppearance(AppearanceCodec.toProto(appearance))
            .build();
        // 自己
        if (session != null) {
            session.send(ServerMessage.newBuilder().setAppearanceUpdate(msg).build());
        }
        // 视野玩家
        for (PlayerEntity nearby : getNearbyPlayers(entity.getX(), entity.getZ())) {
            if (nearby.getId() == entity.getId()) {
                continue;
            }
            PlayerSession ns = nearby.getSession();
            if (ns != null) {
                ns.send(ServerMessage.newBuilder().setAppearanceUpdate(msg).build());
            }
        }
        log.info("[AOI] {} (id={}) appearance updated: body={} weapon={}",
            entity.getName(), pid,
            appearance.getBodyModelIdcode() != 0 ? appearance.getBodyModelIdcode() : (appearance.getBodyModel().isEmpty() ? "-" : appearance.getBodyModel()),
            appearance.getWeaponDorp().isEmpty() ? "-" : appearance.getWeaponDorp());
    }

    private void checkVisibility(PlayerEntity moved,
                                 int oldGridX, int oldGridZ,                                 int newGridX, int newGridZ) {
        long eid = moved.getId();
        double mx = moved.getX();
        double mz = moved.getZ();
        Set<Long> visible = visiblePlayers.computeIfAbsent(eid, k -> ConcurrentHashMap.newKeySet());

        // 1) 新进入 CONNECT 半径的玩家 → 双向 Appear
        StringBuilder appearLog = new StringBuilder();
        for (PlayerEntity other : getNearbyPlayers(mx, mz, VIEW_RANGE)) {
            long oid = other.getId();
            if (oid == eid) continue;
            if (visible.add(oid)) {
                moved.getSession().send(ServerMessage.newBuilder()
                    .setPlayerAppear(buildAppear(other)).build());
                other.getSession().send(ServerMessage.newBuilder()
                    .setPlayerAppear(buildAppear(moved)).build());
                visiblePlayers.computeIfAbsent(oid, k -> ConcurrentHashMap.newKeySet()).add(eid);
                appearLog.append(other.getName()).append(",");
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
                PlayerEntity other = entitiesById.get(oid);
                if (other != null) {
                    PlayerSession otherSession = other.getSession();
                    if (otherSession != null) {
                        otherSession.send(ServerMessage.newBuilder()
                            .setPlayerDisappear(S2C_PlayerDisappear.newBuilder()
                                .setPlayerId(other.getCharId()).build())
                            .build());
                    }
                    Set<Long> otherVisible = visiblePlayers.get(oid);
                    if (otherVisible != null) otherVisible.remove(eid);
                }
                PlayerSession self = moved.getSession();
                if (self != null) {
                    if (other != null) {
                        self.send(ServerMessage.newBuilder()
                            .setPlayerDisappear(S2C_PlayerDisappear.newBuilder()
                                .setPlayerId(other.getCharId()).build())
                            .build());
                    } else {
                        // 实体已被摘除（teardown 与本广播并发）→ **不发**：拿不到 charId，
                        // 而把运行时 id 当 playerId 发出去客户端只会更糊涂（它按 charId 匹配，删不掉那具模型）。
                        // 漏掉的这条不丢信息：onPlayerLeave 已经向视野内广播过同一具实体的 Disappear。
                        log.warn("[AOI] 未向 {} 下发 {} 的 Disappear：该实体已不在线（拿不到 charId）",
                            moved.getCharId(), oid);
                    }
                }
                disappearLog.append(oid).append(",");
            }
        }
        toRemove.forEach(visible::remove);

        if (!appearLog.isEmpty() || !disappearLog.isEmpty()) {
            log.info("[AOI] {} (id={}) grid {},{}->{},{} appear=[{}] disappear=[{}] pos=({},{})",
                moved.getName(), eid, oldGridX, oldGridZ, newGridX, newGridZ, appearLog, disappearLog, (float) mx, (float) mz);
        }
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
