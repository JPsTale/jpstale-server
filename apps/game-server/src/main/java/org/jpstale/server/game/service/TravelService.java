package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.map.FieldCatalog;
import org.jpstale.common.service.map.FieldInfo;
import org.jpstale.common.service.map.FieldInfo.WarpDestination;
import org.jpstale.common.service.map.FieldInfo.WarpGate;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.item.ItemService;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.GameMap;
import org.jpstale.server.game.model.Npc;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.S2C_ItemRemove;
import org.jpstale.server.proto.base.S2C_ItemUpdate;
import org.jpstale.server.proto.base.S2C_TravelOpen;
import org.jpstale.server.proto.base.ServerMessage;
import org.jpstale.server.proto.base.TravelOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 传送的**入口层**：走图门 / NPC 付费传送 / 翅膀传送门网络 / Teleport Core 选图。
 *
 * 搬运本体一律 {@link TeleportService#teleport}（全服唯一实现，等级门槛在里面收口）；
 * 本类只回答设计文档（docs/传送系统-原版机制与方案设计.md）§7 的三件事：
 * <ol>
 *   <li>**触发**（§2）：WorldService.tick 每玩家调 {@link #checkGates} —— mode 0/1 门直接传
 *       （原版 `field.cpp CheckWarpGate`：随机出口/3s 冷却/等级门），mode 2 门先**吸附到门心**
 *       （原版 `field.cpp:222-224` 的 SetPosi）再发 {@link S2C_TravelOpen}；</li>
 *   <li>**目的地表**（§7.2）：四张 Java 代码表（每行带 note 依据），**一律不携带等级**
 *       —— 等级唯一判定源是 `gamedb.maplist.levelreq`（用户 2026-09-28 定调）；</li>
 *   <li>**C2S_TravelUse**（§7.1）：客户端只回 `kind + target(map_id)`，目的地由服务端查表
 *       （EU `NeverSinkTeleportRequest{fieldID}` 同形），并做**使用时上下文复核**
 *       （人还在 NPC 旁 / 门心附近 / 卷轴还在背包 —— EU 不查这些，我们查）。</li>
 * </ol>
 *
 * 与原版的有意偏离（§8 偏离清单）：未识别的目的地 = 可见拒绝（堵 EU switch 无 default 的洞）；
 * 失败在扣费前（先校验后扣钱，与 EU NeverSink 同序）；不做祝福税（无城堡系统）。
 */
@Slf4j
@Service
public class TravelService {

    // ================================ kind（与 proto 注释同步） ================================

    public static final int KIND_NPC_TAB = 1;
    public static final int KIND_WING_GATE = 2;
    public static final int KIND_TELEPORT_CORE = 3;

    // ================================ 表 ①：NPC 付费传送（P2） ================================

    /**
     * `npclist.teleportid` = EU `sUnitInfo.TeleportID`：点击 NPC 时服务端发
     * `PKTHDR_TeleportEvent`（EU `Server/server/unitserver.cpp:444`），客户端 `PHTeleport`
     * 按事件码分流（EU `game/game/CharacterGame.cpp:1708-1739`）。
     * **等级不进表**：一律 `canEnter(目标图)` 查 DB；EU 客户端写死的 105/10 级只作佐证。
     * **费用**：EU 此路径无扣费证据 → 0（设计文档 §9 待裁定 1；不编费用）。
     * 我们库里对得上的 NPC（2026-09-26 psql 实查）：mayor_maz=1000 / atlantis_ambassador_rennan=1001 /
     * battle_portal=1002；其余 teleportid（1/2/4/5/21..33）不在 EU case 表 → 未识别，不服务。
     */
    public record NpcTeleport(int teleportId, int destMap, long cost, String note) {}

    public static final List<NpcTeleport> NPC_TELEPORTS = List.of(
        new NpcTeleport(1000, 3, 0,
            "硬证：EU PHTeleport case 1000 → MAPID_RicartenTown=3；库内 mayor_maz.teleportid=1000"),
        new NpcTeleport(1001, 45, 0,
            "硬证：EU case 1001 → MAPID_Atlantis=45；库内 atlantis_ambassador_rennan.teleportid=1001"),
        new NpcTeleport(1002, 49, 0,
            "硬证：EU case 1002 → MAPID_BattleTown=49（EU 把 105 级判断写成 >=0 是真 bug；等级以 DB 为准）；"
          + "库内 battle_portal.teleportid=1002"),
        new NpcTeleport(1003, 5, 0,
            "硬证：EU case 1003 → MAPID_CastleOfTheLost=5（EU 文案 10 级只作参考，判定走 DB）")
    );

    // ================================ 表 ②：翅膀传送门网络（P3） ================================

    /**
     * 9 门 → 目的图。逐字照抄 11 职业版客户端 `sinbaram/sinWarpGate.cpp:15`
     * `int sinWarpGateCODE[10] = { 3,21,18,1,6,9,12,29,37 };`（Ricarten/Pillai/…/ChaosPost）。
     * 落点 = 目的图的**翅膀出口点**（原版 `PosWarpOut`：目的图那扇 mode 2 自指门的出口坐标，
     * `field.cpp:173-178`；见 {@link #wingGateLanding}）。
     */
    public static final int[] WING_GATE_MAPS = {3, 21, 18, 1, 6, 9, 12, 29, 37};

    /**
     * `int WarpGateUseCost[10] = { 100,300,500,1000,2000,4000 };`（`sinWarpGate.cpp:14`）。
     * 原版费用按**持有翅膀档位**收（不按目的地）：`WarpGateUseCost[GateUseIndex-4]`，
     * GateUseIndex = 4..9 对应 sinQW1|sin01..06（`sinWarpGate.cpp:335-385`）——我们直接用 tier-1 做下标。
     * 前 2 个门（下标 0/1）**永远免费**（`sinWarpGate.cpp:222`：`GateSelect < 2`）；无翅膀（档 0）
     * 时**只有前 2 个门可选**（GateUseIndex=2 → 收费档下标不存在，原版 UI 把后面的门灰掉）。
     */
    public static final long[] WING_GATE_COST_BY_TIER = {100, 300, 500, 1000, 2000, 4000};

    /** 翅膀家族 `sinQW1 = 0x08030000`（`sinItem.h:118`）；六档 `sinQW1|sin01..06`。 */
    public static final int WING_FAMILY = 0x0803;
    public static final int WING_CODE_BASE = 0x08030100;
    public static final int WING_CODE_TOP = 0x08030600;

    /** 无翅膀可选门数：GateUseIndex 默认 2（`sinWarpGate.cpp` SerchUseWarpGate 末尾）。 */
    public static final int NO_WING_GATES = 2;

    // ================================ 表 ③：Teleport Core 目的地（P4） ================================

    /**
     * Teleport Core（BI108 = `0x080B|0x0800`）的选图白名单。
     * 照抄 11 职业版 `sinbaram/HaPremiumItem.cpp:16-55` `TelePort_FieldNum[38][3]` 的
     * **目的地列**（首行 {0,20,0} 在源里被注释，不抄）；源里的**等级列不抄**
     * —— 等级唯一判定源是 DB（用户 2026-09-28 定调），展示值运行时取 `MapManager.levelReqOf`。
     * 原版等级列与 DB 有出入的，以 DB 为准。
     */
    public static final int[] TELEPORT_CORE_MAPS = {
        19, 17, 0, 2, 4, 5, 7, 8, 10, 11,             //  1..10  Vale/Floresta/Acacias/Jardim/Refugio/Castelos/Maldita/Esquecida/Oasis/Batalha
        12, 25, 24, 26, 13, 14, 15, 22, 23, 42,       // 11..20  Proibida/Abelhas/Cogumelos/Sombrio/Calaboucio1-3/Templo1-3
        34, 27, 28, 29, 31, 35, 36, 37, 38, 40, 41,   // 21..31  Lago/Ferro/Coracao/Eura/Gallubia/Congelado/Kelvezu/Ilha/TemploPerdido/Torre1-2
        43, 44, 46, 47, 48, 49                        // 32..37  Torre3/MinaGelo/Lab/Arma/Abismo1-2
    };

    public static final int TELEPORT_CORE_FAMILY = 0x080B;
    public static final int TELEPORT_CORE_CODE = 0x0800;

    public static boolean isTeleportCore(int idCode) {
        return ((idCode >>> 16) & 0xFFFF) == TELEPORT_CORE_FAMILY && (idCode & 0xFFFF) == TELEPORT_CORE_CODE;
    }

    // ================================ 运行时状态 ================================

    /** 门触发冷却：原版 3 秒（`field.cpp:191-194` `dwWarpDelayTime + 3000 > dwPlayTime`）。 */
    private static final long GATE_COOLDOWN_MS = 3000;
    /** 开窗 → 使用的上下文有效期（防"开盘挂机、隔天点确认"）。 */
    private static final long CONTEXT_TTL_MS = 15_000;
    /**
     * 使用时的距离复核：原版确认时 `dist > DIST_TRANSLEVEL_LOW` 拒绝（`field.cpp:2369-2374`，
     * `character.h:22` = 0x320000，**平方距离域**，对翅膀门锚点）。
     */
    private static final long WING_CONFIRM_DIST_SQ = 0x320000L;
    /** NPC 付费传送的使用复核距离（与 NPC 开窗同一口径，见 NpcSpawnService.NPC_INTERACT_RANGE）。 */
    private static final double NPC_RECONFIRM_RANGE = 96.0d;
    /** 门槛拒绝提示的节流（站在门里每 tick 都会判，不能刷屏）。 */
    private static final long DENY_HINT_MS = 5000;

    private final Map<Long, Long> gateCooldownUntil = new ConcurrentHashMap<>();
    private final Map<Long, Long> denyHintAt = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> unknownTeleportIds = new ConcurrentHashMap<>();

    /**
     * 开窗时签发的上下文（设计 §7.1：无会话表 —— options 只是渲染辅助；这里只存
     * "使用时复核"所需的锚点，一次性、TTL 内有效）。
     */
    private record TravelContext(int kind, long npcEntityId, int npcTeleportId,
                                 double anchorX, double anchorY, double anchorZ, long itemUid, long expiresAt) {}

    private final Map<Long, TravelContext> contexts = new ConcurrentHashMap<>();

    @Autowired
    private MapRegionService mapRegionService;
    @Autowired
    private MapManager mapManager;
    @Autowired
    private TeleportService teleportService;
    @Autowired
    private PlayerService playerService;
    @Autowired
    private GoldService goldService;
    @Autowired
    private ItemService itemService;
    @Autowired
    private NpcSpawnService npcSpawnService;
    @Autowired
    private BattleLogService battleLogService;

    // ================================ P1：门触发（WorldService.tick 每玩家调用） ================================

    /**
     * 走图门 / 翅膀门判定。对齐原版 `sFIELD::CheckWarpGate`（`field.cpp:184-309`）：
     * 3s 冷却 → 触发柱命中（dist&lt;size²、dy&lt;height）→ 自指门跳过（那是出口点标记）→
     * mode 0/1 随机出口直传；mode 2 吸附到门心 + 弹目的地盘。
     * **等级判定**不在这里写第二遍：直传交给 {@link TeleportService#teleport} 内部的 canEnter；
     * 但为了不每 tick 弹拒绝提示，先用节流预检拦住（提示 key 与传送共用）。
     */
    public void checkGates(PlayerSession session) {
        PlayerEntity e = session.getEntity();
        if (e == null || e.isDead()) {
            return;
        }
        Player p = playerService.getPlayer(session);
        if (p == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long cd = gateCooldownUntil.get(p.getId());
        if (cd != null && now < cd) {
            return;
        }
        FieldInfo map = FieldCatalog.get().get(e.getMapId());
        if (map == null || map.getWarpGates() == null) {
            return;
        }
        for (WarpGate g : map.getWarpGates()) {
            if (g.getDestinations() == null || g.getDestinations().isEmpty() || g.getSize() <= 0) {
                continue;
            }
            if (!gateHit(e.getX(), e.getY(), e.getZ(), g.getX(), g.getY(), g.getZ(), g.getSize(), g.getHeight())) {
                continue;
            }
            // 门分类（fields.json 的 mode 列当前全 0 —— gen 脚本未带 EU 的 iWarpType，判据不依赖它）：
            //   · 自指门（全部目的地=本图）且本图 ∈ 翅膀门网络 9 门表 → **翅膀门触发器**（弹盘）。
            //     原版该门就是"触发器 + PosWarpOut 落点标记"一体（field.cpp:173-178 + :232-238；
            //     实测 fields.json 的 9 门图全部有自指门，33/52/58 的自指门不在表内）。
            //   · 其余自指门 → 普通门：随机出口=自己 ⇒ 原样 direct（走到门心附近挪到出口点，原版语义）。
            //   · mode==2 的跨图门（将来数据带上 warpType 后）→ 也弹盘。
            boolean selfOnly = g.getDestinations().stream().allMatch(d -> d.getMap() == e.getMapId());
            boolean wingTrigger = selfOnly ? isWingGateMap(e.getMapId()) : g.getMode() == 2;
            if (wingTrigger) {
                triggerWingGate(session, p, e, g, now);
                return;
            }
            if (g.getMode() != 0 && g.getMode() != 1) {
                log.warn("[Travel] map {} 有未知 mode={} 的门（x={},z={}）→ 按普通门处理（请核对 fields.json）",
                    e.getMapId(), g.getMode(), g.getX(), g.getZ());
            }
            triggerDirectGate(session, p, e, g, now);
            return;
        }
    }

    /** 本图是否属于翅膀门网络 9 门表（自指门由此定为翅膀门触发器）。 */
    public static boolean isWingGateMap(int mapId) {
        for (int m : WING_GATE_MAPS) {
            if (m == mapId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 门触发柱命中判定 —— 原版 `field.cpp:210-212` 的纯函数版：
     * `|dx|,|dz| ≤ 1024` 轴向钳制、`dist < size²`、`dy < height`（门 y=0 时**不判高差**，同原版
     * `if (!lpWarpGate->y) dy = 0`）。坐标与 fields.json / 玩家实体同域（world，z 取反已由数据层完成）。
     */
    public static boolean gateHit(double px, double py, double pz,
                                  double gx, double gy, double gz, int size, int height) {
        double dx = px - gx;
        double dz = pz - gz;
        if (Math.abs(dx) > 1024 || Math.abs(dz) > 1024) {
            return false;
        }
        if (dx * dx + dz * dz >= (double) size * size) {
            return false;
        }
        if (gy > 0 && Math.abs(py - gy) >= height) {
            return false;
        }
        return true;
    }

    /** mode 0/1：随机挑一个目的地直传（原版 `rand() % OutGateCount`，`field.cpp:267-275`）。 */
    private void triggerDirectGate(PlayerSession session, Player p, PlayerEntity e, WarpGate g, long now) {
        List<WarpDestination> ds = g.getDestinations();
        WarpDestination d = ds.get(ThreadLocalRandom.current().nextInt(ds.size()));
        MapManager.EnterDeny deny = mapManager.canEnter(p.getLevel(), d.getMap());
        if (deny != MapManager.EnterDeny.OK) {
            hintDenied(session, p, d.getMap(), deny, now);
            return;   // 原版是"等级不够静默穿过"；我们可见提示（偏离清单 #3），不传送
        }
        if (teleportService.teleport(p, d.getMap(), d.getX(), d.getZ(), TeleportService.Reason.WARP_GATE)) {
            gateCooldownUntil.put(p.getId(), now + GATE_COOLDOWN_MS);
        }
    }

    /**
     * mode 2：**吸附 + 弹盘**（原版 `field.cpp:217-245`）。吸附 = 同图短跳到门心（保留朝向——
     * 原版 SetPosi 不改角度），顺带把"判定 tick 与客户端 200ms 漂移"拉正；随后发目的地盘。
     */
    private void triggerWingGate(PlayerSession session, Player p, PlayerEntity e, WarpGate g, long now) {
        teleportService.teleport(p, e.getMapId(), g.getX(), g.getZ(), TeleportService.Reason.WARP_GATE, false);
        gateCooldownUntil.put(p.getId(), now + GATE_COOLDOWN_MS);
        List<TravelOption> options = wingGateOptions(p);
        if (options.isEmpty()) {
            // 显式异常（翅膀门网络表为空才会发生），不静默
            battleLogService.systemKey(session, "travel.noOptions");
            return;
        }
        contexts.put(p.getId(), new TravelContext(KIND_WING_GATE, 0, 0, g.getX(), g.getY(), g.getZ(), 0, now + CONTEXT_TTL_MS));
        session.send(ServerMessage.newBuilder().setTravelOpen(S2C_TravelOpen.newBuilder()
            .setKind(KIND_WING_GATE)
            .setEntityId(0)
            .addAllOptions(options)
            .build()).build());
        log.info("[Travel] {} 踩中翅膀门 map {} ({},{}) → 吸附并弹盘（可选 {} 门）",
            p.getName(), e.getMapId(), (int) g.getX(), (int) g.getZ(), options.size());
    }

    /** 翅膀门盘的可选项：可选门数 = 持有最高翅膀档（无翅 = 前 2 门，全部免费）。 */
    private List<TravelOption> wingGateOptions(Player p) {
        int tier = highestWingTier(p);
        int allowed = allowedGateCount(tier);
        List<TravelOption> options = new ArrayList<>(allowed);
        for (int i = 0; i < allowed; i++) {
            options.add(option(WING_GATE_MAPS[i], wingGateCost(i, tier)));
        }
        return options;
    }

    /** 可选门数：无翅 = {@link #NO_WING_GATES}；有翅 = 全部 9 门（原版 GateUseIndex=4..9 解锁全图门）。 */
    public static int allowedGateCount(int wingTier) {
        return wingTier <= 0 ? NO_WING_GATES : WING_GATE_MAPS.length;
    }

    /**
     * 第 i 个门的费用：前 2 门恒免费；其余按**持有档位**收（原版 `WarpGateUseCost[GateUseIndex-4]`，
     * tier∈1..6 → 下标 tier-1 —— 与目的门无关，这是原版口径，见 sinWarpGate.cpp:243）。
     */
    public static long wingGateCost(int gateIndex, int wingTier) {
        if (gateIndex < NO_WING_GATES) {
            return 0;
        }
        return wingTier <= 0 ? -1 : WING_GATE_COST_BY_TIER[wingTier - 1];
    }

    /** 背包里最高档的翅膀 → tier 1..6；没有 = 0（原版 SerchUseWarpGate 扫背包+手上，`sinWarpGate.cpp:335-385`）。 */
    public int highestWingTier(Player p) {
        int tier = 0;
        for (var it : p.getItems().itemsIn(ItemLocations.BAG_PAGE)) {
            int code = it.getItemCode() == null ? 0 : it.getItemCode();
            if (((code >>> 16) & 0xFFFF) == WING_FAMILY
                    && code >= WING_CODE_BASE && code <= WING_CODE_TOP) {
                tier = Math.max(tier, code - WING_CODE_BASE + 1);
            }
        }
        return tier;
    }

    /**
     * 目的图的**翅膀出口点**（原版 `PosWarpOut`）：找目的图那扇"全部目的地=本图"的自指门
     * （原版 `AddWarpOutGate(pcMap==this)` 记录出口坐标，`field.cpp:173-178`），取出口坐标。
     * ⚠ 不按 mode==2 过滤：fields.json 的 mode 列当前全 0（gen 脚本未带 EU warpType）。
     * 找不到 = 数据缺口，**显式拒绝**（不猜中心）。
     */
    public static double[] wingGateLanding(int destMap) {
        FieldInfo m = FieldCatalog.get().get(destMap);
        if (m == null || m.getWarpGates() == null) {
            return null;
        }
        for (WarpGate g : m.getWarpGates()) {
            if (g.getDestinations() == null || g.getDestinations().isEmpty()) {
                continue;
            }
            if (g.getDestinations().stream().allMatch(d -> d.getMap() == destMap)) {
                WarpDestination d = g.getDestinations().get(0);
                return new double[]{d.getX(), d.getZ()};
            }
        }
        return null;
    }

    // ================================ P2：NPC 传送（开窗由 NpcShopHandler 交互入口调） ================================

    /**
     * 点击带 `teleportid` 的 NPC。表里没有的事件码 = **未识别，不服务**（显式拒绝 + 每码只 WARN 一次；
     * 我们库里的 1/2/4/5/21..33 都属此类 —— EU case 表之外语义不可考，见设计文档 §3.2）。
     */
    public void openNpcTeleport(PlayerSession session, Player p, Npc npc) {
        int tid = npc.getTeleportId();
        NpcTeleport row = null;
        for (NpcTeleport r : NPC_TELEPORTS) {
            if (r.teleportId() == tid) {
                row = r;
                break;
            }
        }
        if (row == null) {
            if (unknownTeleportIds.putIfAbsent((long) tid, Boolean.TRUE) == null) {
                log.warn("[Travel] teleportid={}（npc={}）不在 EU 事件码表（1000..1003）→ 不服务（未识别不猜）",
                    tid, npc.getNpcId());
            }
            battleLogService.systemKey(session, "travel.unknownDestination");
            return;
        }
        long now = System.currentTimeMillis();
        PlayerEntity e = session.getEntity();
        contexts.put(p.getId(), new TravelContext(KIND_NPC_TAB, npc.getId(), tid,
                e == null ? 0 : npc.getX(), 0, e == null ? 0 : npc.getZ(), 0, now + CONTEXT_TTL_MS));
        session.send(ServerMessage.newBuilder().setTravelOpen(S2C_TravelOpen.newBuilder()
            .setKind(KIND_NPC_TAB)
            .setEntityId(npc.getId())
            .addOptions(option(row.destMap(), row.cost()))
            .build()).build());
        log.info("[Travel] {} 打开 NPC 传送 npc={} teleportid={} → 目的地 map {}（费用 {}）",
            p.getName(), npc.getNpcId(), tid, row.destMap(), row.cost());
    }

    // ================================ P4：Teleport Core（useItem 钩子调） ================================

    /**
     * 右键 Teleport Core：**不直接传**，弹选图盘（原版 TCore/HaPremiumItem UI；
     * 我们的目标地白名单 = {@link #TELEPORT_CORE_MAPS}）。
     * 卷轴此刻**不消耗**——确认选图时才扣（"效果能落地才扣道具"，传送系统.md §4）。
     */
    public void openTeleportCore(PlayerSession session, Player p, long itemUid) {
        PlayerEntity e = session.getEntity();
        if (e == null) {
            return;
        }
        // 室内不可用：原版 FIELD_STATE_ROOM（HaPremiumItem.cpp:2164-2168）；我们按 maplist.typemap='Room' 判
        org.jpstale.server.game.model.GameMap gm = mapManager.getMap(e.getMapId());
        if (gm != null && "Room".equalsIgnoreCase(gm.getTypeMap())) {
            battleLogService.systemKey(session, "travel.inRoomDenied");
            return;
        }
        long now = System.currentTimeMillis();
        contexts.put(p.getId(), new TravelContext(KIND_TELEPORT_CORE, 0, 0,
                e.getX(), 0, e.getZ(), itemUid, now + CONTEXT_TTL_MS));
        S2C_TravelOpen.Builder open = S2C_TravelOpen.newBuilder().setKind(KIND_TELEPORT_CORE).setEntityId(0);
        for (int mapId : TELEPORT_CORE_MAPS) {
            open.addOptions(option(mapId, 0));
        }
        session.send(ServerMessage.newBuilder().setTravelOpen(open.build()).build());
        log.info("[Travel] {} 打开 Teleport Core 选图（uid={}，{} 个目的地）",
            p.getName(), itemUid, TELEPORT_CORE_MAPS.length);
    }

    // ================================ C2S_TravelUse ================================

    @GamePacketHandler(ClientMessage.TRAVEL_USE_FIELD_NUMBER)
    public void handleTravelUse(PlayerSession session, ClientMessage message) {
        Player p = playerService.requirePlayer(session);
        if (p == null) {
            return;
        }
        var req = message.getTravelUse();
        int kind = req.getKind();
        int target = req.getTarget();
        long now = System.currentTimeMillis();
        TravelContext ctx = contexts.remove(p.getId());   // 一次性：用掉即作废
        if (ctx == null || ctx.expiresAt() < now || ctx.kind() != kind) {
            log.info("[Travel] {} 的选择请求被拒：无有效上下文（kind={} target={}）",
                p.getName(), kind, target);
            battleLogService.systemKey(session, "travel.expired");
            return;
        }
        switch (kind) {
            case KIND_NPC_TAB -> useNpcTeleport(session, p, ctx, target);
            case KIND_WING_GATE -> useWingGate(session, p, ctx, target, now);
            case KIND_TELEPORT_CORE -> useTeleportCore(session, p, ctx, target);
            default -> log.warn("[Travel] {} 未知 kind={}（可疑）", p.getName(), kind);
        }
    }

    /** NPC 传送确认：复核 NPC 距离（复用交互校验的同一实现）→ 表复核 → 扣费 → 传送到出生点。 */
    private void useNpcTeleport(PlayerSession session, Player p, TravelContext ctx, int target) {
        // 人还在 NPC 旁（开窗后走开了就拒绝；与开窗同一套校验 —— AGENTS #15：同一判定只写一份）
        Npc npc = npcSpawnService.resolveInteractable(session, ctx.npcEntityId(), "shop.outOfRange");
        if (npc == null) {
            return;
        }
        // 目的地必须仍是"这个 NPC 的那一条"（表按 teleportId 键入；target 只是展示序号）
        NpcTeleport row = null;
        for (NpcTeleport r : NPC_TELEPORTS) {
            if (r.teleportId() == ctx.npcTeleportId()) {
                row = r;
                break;
            }
        }
        if (row == null || row.destMap() != target) {
            log.warn("[Travel] {} 的 NPC 传送被拒：target={} 与 npc.teleportid={} 不匹配（可疑）",
                p.getName(), target, ctx.npcTeleportId());
            battleLogService.systemKey(session, "travel.expired");
            return;
        }
        if (!teleportService.canTeleportTo(p, target, TeleportService.Reason.WARP_GATE)) {
            return;   // 等级/未开放：canTeleportTo 已给可见提示
        }
        int[] pos = teleportService.randomValidStartPoint(target);
        if (pos == null) {
            battleLogService.systemKey(session, "travel.noLanding");
            return;
        }
        if (row.cost() > 0) {
            GoldService.Result r = goldService.add(session, p, -row.cost(), "travel_npc");
            if (r != GoldService.Result.OK) {
                battleLogService.systemKey(session, "travel.notEnoughGold");
                return;
            }
        }
        teleportService.teleport(p, target, pos[0], pos[1], TeleportService.Reason.WARP_GATE);
    }

    /**
     * 翅膀门确认：距离复核（原版确认时的 DIST_TRANSLEVEL_LOW 复核，`field.cpp:2369-2374`）→
     * 档位/费用复核 → 扣费 → 传到目的图的翅膀出口点。
     */
    private void useWingGate(PlayerSession session, Player p, TravelContext ctx, int target, long now) {
        PlayerEntity e = session.getEntity();
        if (e == null) {
            return;
        }
        double dx = e.getX() - ctx.anchorX();
        double dy = e.getY() - ctx.anchorY();
        double dz = e.getZ() - ctx.anchorZ();
        if (dx * dx + dy * dy + dz * dz > WING_CONFIRM_DIST_SQ) {
            log.info("[Travel] {} 的翅膀门确认被拒：距门心过远（可疑）", p.getName());
            battleLogService.systemKey(session, "travel.tooFar");
            return;
        }
        int idx = -1;
        for (int i = 0; i < WING_GATE_MAPS.length; i++) {
            if (WING_GATE_MAPS[i] == target) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            log.warn("[Travel] {} 的翅膀门确认被拒：target={} 不在门网络表（可疑）", p.getName(), target);
            battleLogService.systemKey(session, "travel.expired");
            return;
        }
        int tier = highestWingTier(p);
        if (idx >= allowedGateCount(tier)) {
            battleLogService.systemKey(session, "travel.wingRequired");
            return;
        }
        MapManager.EnterDeny deny = mapManager.canEnter(p.getLevel(), target);
        if (deny != MapManager.EnterDeny.OK) {
            hintDenied(session, p, target, deny, now);
            return;
        }
        double[] landing = wingGateLanding(target);
        if (landing == null) {
            log.warn("[Travel] 目的图 {} 没有 mode2 自指门（翅膀出口点数据缺失）→ 拒绝", target);
            battleLogService.systemKey(session, "travel.noLanding");
            return;
        }
        long cost = wingGateCost(idx, tier);
        if (cost < 0) {
            // 数据性异常：收费门 + 无翅膀（allowedGateCount 已拦，正常到不了）——显式拒绝
            battleLogService.systemKey(session, "travel.wingRequired");
            return;
        }
        if (cost > 0) {
            GoldService.Result r = goldService.add(session, p, -cost, "travel_wing_gate");
            if (r != GoldService.Result.OK) {
                battleLogService.systemKey(session, "travel.notEnoughGold");
                return;
            }
        }
        teleportService.teleport(p, target, landing[0], landing[1], TeleportService.Reason.WARP_GATE);
    }

    /** Teleport Core 确认：卷轴还在 → 白名单复核 → 等级 → 扣卷轴 → 传送到出生点（效果能落地才扣）。 */
    private void useTeleportCore(PlayerSession session, Player p, TravelContext ctx, int target) {
        var it = p.getItems().byUid(ctx.itemUid());
        if (it == null || it.isDeleted() || it.getLocation() != ItemLocations.BAG_PAGE) {
            log.info("[Travel] {} 的 Teleport Core 确认被拒：卷轴已不在背包（uid={}）", p.getName(), ctx.itemUid());
            battleLogService.systemKey(session, "travel.scrollGone");
            return;
        }
        boolean listed = false;
        for (int m : TELEPORT_CORE_MAPS) {
            if (m == target) {
                listed = true;
                break;
            }
        }
        if (!listed) {
            log.warn("[Travel] {} 的 Teleport Core 确认被拒：target={} 不在白名单（可疑）", p.getName(), target);
            battleLogService.systemKey(session, "travel.expired");
            return;
        }
        if (!teleportService.canTeleportTo(p, target, TeleportService.Reason.ITEM)) {
            return;
        }
        int[] pos = teleportService.randomValidStartPoint(target);
        if (pos == null) {
            battleLogService.systemKey(session, "travel.noLanding");
            return;
        }
        var used = itemService.consumeAt(p, ctx.itemUid(), 1);
        if (used == null) {
            battleLogService.systemKey(session, "travel.scrollGone");
            return;
        }
        // 消耗结果推送（与 ItemNetworkHandler.pushAfterUse 同口径：有剩余推 Update，归零推 Remove）
        if (used.getCount() > 0) {
            session.send(ServerMessage.newBuilder()
                .setItemUpdate(S2C_ItemUpdate.newBuilder().setItem(org.jpstale.server.game.item.ItemNetworkHandler.toProto(used)).build())
                .build());
        } else {
            session.send(ServerMessage.newBuilder()
                .setItemRemove(S2C_ItemRemove.newBuilder().setUid(used.getId()).build())
                .build());
        }
        teleportService.teleport(p, target, pos[0], pos[1], TeleportService.Reason.ITEM);
        log.info("[Travel] {} 使用 Teleport Core → map {} ({},{})，消耗 uid={}",
            p.getName(), target, (int) pos[0], (int) pos[1], ctx.itemUid());
    }

    // ================================ 工具 ================================

    /** 一条展示用选项：等级展示值运行时取 DB（表里永远不携带等级）；名字取 DB maplist.name。 */
    private TravelOption option(int mapId, long cost) {
        GameMap gm = mapManager.getMap(mapId);
        return TravelOption.newBuilder()
            .setMapId(mapId)
            .setName(gm == null || gm.getName() == null ? "map " + mapId : gm.getName())
            .setCost((int) cost)
            .setLevelReq(mapManager.levelReqOf(mapId))
            .build();
    }

    /** 门槛拒绝的节流提示（与传送入口共用同一批 key；5s 内不重复）。 */
    private void hintDenied(PlayerSession session, Player p, int mapId, MapManager.EnterDeny deny, long now) {
        Long last = denyHintAt.get(p.getId());
        if (last != null && now - last < DENY_HINT_MS) {
            return;
        }
        denyHintAt.put(p.getId(), now);
        if (deny == MapManager.EnterDeny.NOT_OPEN) {
            battleLogService.systemKey(session, "chat.cmd.teleportNotOpen");
        } else {
            battleLogService.systemKey(session, "chat.cmd.teleportLevelLow", Map.of(
                "level", String.valueOf(mapManager.levelReqOf(mapId))));
        }
    }
}
