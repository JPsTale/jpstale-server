package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerMoveState;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_PlayerTeleport;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.jpstale.server.common.enums.packets.CharacterRace;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送（不连续位移）的**唯一入口**。
 *
 * 游戏里会有一堆传送来源：回城卷轴 / 传送卷轴 / 传送门（warp gate）/ 婚戒 / GM 命令 / 脱困。
 * 它们的差别只有两件事 —— **落点怎么来** 和 **代价与限流**；"怎么把玩家搬过去、怎么通知两端"
 * 是同一件事，必须只有一份实现（否则就是 AGENTS.md 里那条"同一判定写第二份就是 bug 的种子"）。
 * 所以：
 *
 *   · {@link #teleport} —— 机制：按目标图地形补 y → 写 PlayerEntity 坐标/朝向/状态 → 跨图摘挂 AOI
 *     → 本人收 `S2C_PlayerTeleport`、视野内其他人收同一条播报 → INFO 日志。**零代价、零策略**。
 *   · 落点解析（{@link #nearestValidStartPoint} / {@link #villageStartPoint}）——
 *     只回答"这个语义目标对应哪组坐标"，不做任何搬运。
 *   · 各来源自己负责代价/限流/回执（例：脱困有 30s 冷却；卷轴要扣道具；回城有吟唱）。
 *
 * **客户端不接受坐标参数**：C2S 只表达意图（`C2S_Unstuck` 是空消息），落点一律服务端算。
 * 传送门同理 —— 客户端报"我踩到了哪个 gate"，目标图由服务端查表，不信客户端给的坐标。
 *
 * 客户端侧对应 `WorldView.applyTeleport`（本人）与 `teleportRemote`（旁观者）。
 */
@Slf4j
@Service
public class TeleportService {

    /** 广播半径（与 CombatService 同值；AOI 落地后由 AOI 决定实际收件人） */
    private static final float AOI_BROADCAST_RANGE = 50f;

    /** 种族村庄：job ≤ 4 = 神殿村，其余 = 菲拉村（对齐原版 RESTART_TOWN 的两张图） */
    private static final int TOWN_MAP_TEMPLE = 3;

    /**
     * 传送/复活后的**初始朝向**（用户 2026-09-13 指定 -π）。
     * 客户端 `charGroup.rotation.y` 直接吃这个值，故单位是**弧度**（-π 即面向 -z）。
     */
    private static final float INITIAL_ANGLE = (float) -Math.PI;
    private static final int TOWN_MAP_PILAI = 21;

    /** 脱困冷却（同一角色两次之间的最小间隔） */
    private static final long UNSTUCK_COOLDOWN_MS = 30_000;

    /**
     * 传送原因。编码写进 `S2C_PlayerTeleport.reason`（proto 里有同一份注释），
     * 客户端目前只用来做展示/特效，不参与任何判定。
     */
    public enum Reason {
        OTHER(0), UNSTUCK(1), GM(2), ITEM(3), WARP_GATE(4), WEDDING_RING(5), RESPAWN(6);

        public final int code;

        Reason(int code) {
            this.code = code;
        }
    }

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private MapRegionService mapRegionService;

    @Autowired
    private MapManager mapManager;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private BattleLogService battleLogService;

    // ================================ 唯一入口 ================================

    /**
     * 把玩家搬到 (mapId, x, z)。**这是全服唯一的"不连续位移"实现**。
     *
     * @return true = 已搬（并已广播）；false = 目标图不存在或玩家不在场（调用方据此决定回执）
     */
    public boolean teleport(Player player, int mapId, double x, double z, Reason reason) {
        PlayerSession session = player.getSession();
        PlayerEntity entity = session != null ? session.getEntity() : null;
        if (entity == null) {
            log.warn("TELEPORT {} 失败：无 PlayerEntity（reason={}）", player.getName(), reason);
            return false;
        }
        if (!mapRegionService.isMap(mapId)) {
            log.warn("TELEPORT {} 失败：目标图 {} 不存在（reason={}）", player.getName(), mapId, reason);
            return false;
        }
        // 等级门槛：**在这里收口**，于是卷轴/传送门/NPC/婚戒全都自动带上（不必各处各写一份）。
        if (reason != Reason.RESPAWN && reason != Reason.UNSTUCK && reason != Reason.GM) {
            MapManager.EnterDeny deny = mapManager.canEnter(player.getLevel(), mapId);
            if (deny != MapManager.EnterDeny.OK) {
                log.info("TELEPORT {} 被门槛拒绝：reason={} map {} level {} (levelreq={}, deny={})",
                    player.getName(), reason, mapId, player.getLevel(), mapManager.levelReqOf(mapId), deny);
                notifyDenied(session, mapId, deny);
                return false;
            }
        }

        int fromMap = entity.getMapId();
        double fromX = entity.getX(), fromZ = entity.getZ();

        // y 必须按**目标地图**的地形算：落点数据只有 {x,z}，不设 y 就会带着上一张图的高度，
        // 客户端随即自由落体（FALLDOWN）并把坏 y 写回存档 → 下次登录继续沉（用户 2026-09-12 报）。
        double terrainY = mapRegionService.getHeight(mapId, x, z);
        if (terrainY <= 0) {
            // 可见地降级，不静默塞 0：调用方应优先用 nearestValidStartPoint 选过落点
            log.warn("TELEPORT {} 落点 map {} ({},{}) 无可站立地面（getHeight=0）→ 保留原 y {}，请核对落点数据",
                player.getName(), mapId, (int) x, (int) z, entity.getY());
        }

        boolean switchedMap = fromMap != mapId;
        entity.setX(x);
        entity.setZ(z);
        if (terrainY > 0) {
            entity.setY(terrainY);
        }
        // 朝向复位为**初始角度**（用户 2026-09-13 指定：-π）。
        // 广播给本人与旁观者的 S2C_PlayerTeleport 都读 entity.getAngle()，所以在这里改一处即可，
        // 传送（卷轴/传送门/婚戒/GM）、复活、脱困全都自动带上。
        entity.setAngle(INITIAL_ANGLE);
        entity.setMoveState(PlayerMoveState.IDLE);      // 打断移动/攻击态（站立）
        entity.setLastSyncedAnimState(0x0040);          // 复位动画去重基线（STAND）
        if (switchedMap) {
            entity.setMapId(mapId);
            aoiManager.onPlayerLeave(entity);
            aoiManager.removePlayer(entity);
            aoiManager.addPlayer(entity);
            aoiManager.onPlayerEnter(entity);
        }

        // 一条消息两个受众：本人按 player_id==自己 判定后搬自己；旁观者挪 actor。
        // 自己单独发一份（不依赖"区域广播是否包含自己" —— AOI 落地后那一点可能变）。
        S2C_PlayerTeleport teleport = S2C_PlayerTeleport.newBuilder()
            .setPlayerId(player.getId())
            .setMapId(mapId)
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) x).setY((float) Math.max(0, terrainY)).setZ((float) z).build())
            .setAngle((float) entity.getAngle())
            .setReason(reason.code)
            .build();
        ServerMessage msg = ServerMessage.newBuilder().setPlayerTeleport(teleport).build();
        if (session != null) {
            session.send(msg);
        }
        messageSender.broadcastToArea(mapId, (float) x, (float) z, AOI_BROADCAST_RANGE, msg);

        log.info("TELEPORT {} reason={} map {} ({},{}) → map {} ({},{}) y={} switched={}",
            player.getName(), reason, fromMap, (int) fromX, (int) fromZ, mapId, (int) x, (int) z,
            (int) terrainY, switchedMap);
        return true;
    }

    /** 门槛拒绝的可见回执（不静默：玩家要知道为什么没传过去） */
    private void notifyDenied(PlayerSession session, int mapId, MapManager.EnterDeny deny) {
        if (session == null) {
            return;
        }
        if (deny == MapManager.EnterDeny.NOT_OPEN) {
            battleLogService.systemKey(session, "chat.cmd.teleportNotOpen");
        } else {
            battleLogService.systemKey(session, "chat.cmd.teleportLevelLow", Map.of(
                "level", String.valueOf(mapManager.levelReqOf(mapId))));
        }
    }

    /**
     * 只问一次"这个目标能不能传"，**不改任何状态**。
     * 用途：消耗道具的路径要"先校验、再扣道具"——扣完才发现传不过去就得退还，麻烦且容易出错。
     * 判定与 {@link #teleport} 内部用的是同一份（不是又写一遍）。
     */
    public boolean canTeleportTo(Player player, int mapId, Reason reason) {
        PlayerSession session = player.getSession();
        if (session == null || session.getEntity() == null || !mapRegionService.isMap(mapId)) {
            return false;
        }
        if (reason == Reason.RESPAWN || reason == Reason.UNSTUCK || reason == Reason.GM) {
            return true;
        }
        MapManager.EnterDeny deny = mapManager.canEnter(player.getLevel(), mapId);
        if (deny != MapManager.EnterDeny.OK) {
            notifyDenied(session, mapId, deny);
            return false;
        }
        return true;
    }

    // ================================ 落点解析 ================================

    /**
     * 该图**离 (fromX,fromZ) 最近的、脚下确实有可站立地面**的 StartPoint。
     * 逐个按距离试，跳过 `getHeight<=0` 的点 —— 出生点数据与地形不匹配时直接送过去会掉下去，
     * 所以"取最近"必须带有效性校验（这是"靠谱"与"看上去对"的区别）。
     *
     * @return [x, z]，该图没有可用出生点则 null
     */
    public int[] nearestValidStartPoint(int mapId, double fromX, double fromZ) {
        List<int[]> pts = mapRegionService.startPoints(mapId);
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        List<int[]> sorted = new ArrayList<>(pts);
        sorted.sort(Comparator.comparingDouble(p -> {
            double dx = p[0] - fromX, dz = p[1] - fromZ;
            return dx * dx + dz * dz;
        }));
        for (int[] p : sorted) {
            if (mapRegionService.getHeight(mapId, p[0], p[1]) > 0) {
                return p;
            }
            log.warn("TELEPORT map {} StartPoint ({},{}) 无可站立地面 → 试下一个", mapId, p[0], p[1]);
        }
        return null;
    }

    /**
     * 该图**随机**一个脚下有地面的 StartPoint。
     * 对齐原版回城道具：`WarpStartField` 是 `cnt = rand() % StartPointCnt` 再就近吸附
     * ——故意随机，免得同一时刻回城的人全叠在一个点上。
     * 逐个随机试（最多全试一遍），跳过无地面的点；全都无地面 → null。
     */
    public int[] randomValidStartPoint(int mapId) {
        List<int[]> pts = mapRegionService.startPoints(mapId);
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        List<int[]> shuffled = new ArrayList<>(pts);
        java.util.Collections.shuffle(shuffled);
        for (int[] p : shuffled) {
            if (mapRegionService.getHeight(mapId, p[0], p[1]) > 0) {
                return p;
            }
        }
        log.warn("TELEPORT map {} 的所有 StartPoint 都无可站立地面", mapId);
        return null;
    }

    /**
     * 本族村庄出生点 [x, z]（坦普族=理查登，魔灵族=菲拉）。
     * **必须挑"有可站立地面"的点**：村庄数据里可能有点落在虚空，直接送过去玩家会自由落体
     * 掉出地图（用户 2026-09-13 实测回村庄掉出去）。与脱困用同一套判据（getHeight > 0）。
     */
    public int[] villageStartPoint(Player player) {
        int mapId = villageMapId(player);
        int[] p = nearestValidStartPoint(mapId, 0, 0);
        if (p != null) {
            return p;
        }
        log.warn("villageStartPoint: map {} 没有有效出生点（请核对 startPoint 数据）", mapId);
        return null;
    }

    /**
     * 按目的地表的落点策略解析出实际坐标 [x, z]。
     * 策略字符串来自 `ItemDestination.landing`（代码常量表；不查 DB）：
     *   · startpoint-random  —— 原版回城道具语义（`WarpStartField` 随机出生点）
     *   · startpoint-nearest —— 离 (fromX,fromZ) 最近的有效出生点（原版 `GetStartPoint(x,z)`，死亡复活用的那条）
     *   · fixed              —— 表里写死的 fixedx/fixedz
     *
     * @return null = 该图没有可用落点（调用方必须**可见地**拒绝，别静默把人送进虚空）
     */
    public double[] resolveLanding(int mapId, String landing, Integer fixedX, Integer fixedZ,
                                   double fromX, double fromZ) {
        switch (landing == null ? "" : landing) {
            case "fixed": {
                if (fixedX != null && fixedZ != null) {
                    return new double[]{fixedX, fixedZ};
                }
                return null;
            }
            case "startpoint-random": {
                // 只认"有可站立地面"的出生点（randomValidStartPoint 内部用 getHeight>0 筛）。
                int[] p = randomValidStartPoint(mapId);
                if (p == null) {
                    p = nearestValidStartPoint(mapId, fromX, fromZ);
                }
                if (p == null) {
                    log.warn("TELEPORT map {} 没有可用出生点（startPoint 数据缺失或全部无地面）→ 拒绝传送", mapId);
                }
                return p != null ? new double[]{p[0], p[1]} : null;
            }
            default: {
                // 未知策略**不猜**：以前落到 default 会被当成 startpoint-random，
                // 于是 landing="none"（表示"不是固定目的地"）会被静默变成一次随机传送。
                log.warn("未知落点策略 '{}'（map {}）→ 拒绝传送，不猜", landing, mapId);
                return null;
            }
        }
    }

    /** 本族村庄的图号（给需要"送到村庄"的调用方，例如死亡复活的选项 2、回城卷轴）。
     *  族判据收口在 CharacterRace（含刺客 9 / 格斗家 11，用户 2026-09-13 指正）。 */
    public int villageMapId(Player player) {
        return CharacterRace.isTempskron(player.getJob()) ? TOWN_MAP_TEMPLE : TOWN_MAP_PILAI;
    }

    // ==================== 物品 → 固定目的地（照原版写在代码里） ====================

    /**
     * 一条"物品 → 固定图"的目的地。
     *
     * **为什么写在代码里而不是 DB 表**（用户 2026-09-13 定）：原版就是硬编码 ——
     * `character.cpp` 的 `switch (UseEtherCoreCode)` 把 `sinEC1|sin01/02/04` 分别送到
     * `START_FIELD_NUM(3)/NEBISCO(9)/MORYON(21)`（`field.h:106-108`），**没有任何数据表**。
     * 这项数据只有几条、几乎不变，价值在"依据可查"；为它建表会让运行库承担一次 schema 变更，
     * 收益不成比例。要新增一种"物品→固定图"的传送，就在这里加一行（`note` 必须写依据）。
     *
     * @param family  idcode 高 16 位（原版 `sinITEM_MASK2` 口径）
     * @param code    idcode 低 16 位；0 = 该族通用
     */
    public record ItemDestination(int family, int code, int destMap, String landing, String note) {}

    private static final java.util.List<ItemDestination> ITEM_DESTINATIONS = java.util.List.of(
        new ItemDestination(0x0601, 0x0100, 3, "startpoint-random",
            "硬证：character.cpp switch(UseEtherCoreCode) sinEC1|sin01 → WarpStartField(START_FIELD_NUM=3)；field.h:106 START_FIELD_NUM=3 = Ricarten"),
        new ItemDestination(0x0601, 0x0200, 9, "startpoint-random",
            "硬证：sinEC1|sin02 → START_FIELD_NEBISCO=9 = Navisko"),
        new ItemDestination(0x0601, 0x0300, 45, "startpoint-random",
            "推断：8 职业客户端 switch 未覆盖该码（物品存在）；wartale 指南 5 镇中 EC101/102/104 已定，余下 Atlantis=45。待核对"),
        new ItemDestination(0x0601, 0x0400, 21, "startpoint-random",
            "硬证：sinEC1|sin04 → START_FIELD_MORYON=21 = Phillai"),
        new ItemDestination(0x0601, 0x0500, 0, "none",
            "不是回城卷轴：EC105 = Union Core（OpenItem 名 Union Stick Core），目标是**被签名的玩家**（动态），"
          + "原版另有 sinInvenTory 的 Union/签名机制。走婚戒那一类，**禁止当回城卷轴**"),
        new ItemDestination(0x080B, 0x0800, 0, "none",
            "不是固定目的地：BI108 = Teleport Core（商城货），原版要玩家**自己选图**（弹地图列表），"
          + "将来接传送网络表 + Reason.WARP_GATE")
    );

    /**
     * 物品 idcode → 固定目的地；没有则 null。
     * 先精确匹配（family+code），再退到族通用（code=0）。`destMap<=0` = 该物品**不是**固定目的地
     * （调用方据此走各自分支，别默认送某张图）。
     */
    public ItemDestination destinationOf(int idCode) {
        if (idCode == 0) {
            return null;
        }
        int family = (idCode >>> 16) & 0xFFFF;
        int code = idCode & 0xFFFF;
        ItemDestination general = null;
        for (ItemDestination d : ITEM_DESTINATIONS) {
            if (d.family() != family) {
                continue;
            }
            if (d.code() == code) {
                return d.destMap() > 0 ? d : null;
            }
            if (d.code() == 0) {
                general = d;
            }
        }
        return general != null && general.destMap() > 0 ? general : null;
    }

    // ================================ 脱困（传送的一个入口） ================================

    /** 脱困落点的兜底链走完仍失败时的结果；`cooldownLeftSec>0` = 还在冷却 */
    public record UnstuckOutcome(boolean ok, int mapId, int x, int z, int cooldownLeftSec) {}

    private final Map<Long, Long> unstuckAt = new ConcurrentHashMap<>();

    /**
     * 脱困：送到**本图离玩家最近的、有效的 StartPoint**；本图没有出生点数据 → 回本族村庄。
     * 零代价（不动经验/金币/血量），30 秒冷却。
     */
    public UnstuckOutcome unstuck(Player player) {
        PlayerSession session = player.getSession();
        PlayerEntity entity = session != null ? session.getEntity() : null;
        if (entity == null) {
            return new UnstuckOutcome(false, 0, 0, 0, 0);
        }
        long now = System.currentTimeMillis();
        if (unstuckAt.size() > 4096) {   // 冷却表只增不减 → 偶尔清一次过期项
            unstuckAt.entrySet().removeIf(e -> now - e.getValue() > UNSTUCK_COOLDOWN_MS);
        }
        Long last = unstuckAt.get(player.getId());
        if (last != null && now - last < UNSTUCK_COOLDOWN_MS) {
            return new UnstuckOutcome(false, 0, 0, 0, (int) ((UNSTUCK_COOLDOWN_MS - (now - last)) / 1000 + 1));
        }

        int fromMap = entity.getMapId();
        double fromX = entity.getX(), fromZ = entity.getZ();
        int mapId = fromMap;
        int[] p = nearestValidStartPoint(mapId, fromX, fromZ);
        if (p == null) {
            // 本图没有可用出生点 → 回本族村庄（村庄也没有就放弃，不静默乱送）
            int village = villageMapId(player);
            int[] vp = nearestValidStartPoint(village, 0, 0);
            if (vp == null) {
                log.warn("UNSTUCK {} 本图 map {} 与村庄 map {} 都没有可用出生点", player.getName(), fromMap, village);
                return new UnstuckOutcome(false, 0, 0, 0, 0);
            }
            log.info("UNSTUCK {} map {} 无可用 StartPoint → 回村庄 map {}", player.getName(), fromMap, village);
            mapId = village;
            p = vp;
        }

        if (!teleport(player, mapId, p[0], p[1], Reason.UNSTUCK)) {
            return new UnstuckOutcome(false, 0, 0, 0, 0);
        }
        unstuckAt.put(player.getId(), now);
        log.info("UNSTUCK {} 脱困 ({},{}) map {} → map {} ({},{})",
            player.getName(), (int) fromX, (int) fromZ, fromMap, mapId, p[0], p[1]);
        return new UnstuckOutcome(true, mapId, p[0], p[1], 0);
    }

    /**
     * `C2S_Unstuck`：系统菜单"脱离卡死"按钮的报文入口。
     * 客户端只表达"我想脱困"，不带任何参数 —— 落点由服务端算。
     */
    @GamePacketHandler(ClientMessage.UNSTUCK_FIELD_NUMBER)
    public void handleUnstuckPacket(PlayerSession session, ClientMessage message) {
        if (session == null || !session.isPlaying()) {
            return;
        }
        respondUnstuck(session, unstuck(playerService.getOrCreate(session)));
    }

    /** 脱困的统一回执（报文入口与聊天框 `/unstuck` 共用，避免两处文案/判定漂移） */
    public void respondUnstuck(PlayerSession session, UnstuckOutcome r) {
        if (r.ok()) {
            battleLogService.systemKey(session, "chat.cmd.unstuckDone", Map.of(
                "map", String.valueOf(r.mapId()),
                "x", String.valueOf(r.x()),
                "z", String.valueOf(r.z())));
        } else if (r.cooldownLeftSec() > 0) {
            battleLogService.systemKey(session, "chat.cmd.unstuckCooldown",
                Map.of("sec", String.valueOf(r.cooldownLeftSec())));
        } else {
            battleLogService.systemKey(session, "chat.cmd.unstuckFailed");
        }
    }
}
