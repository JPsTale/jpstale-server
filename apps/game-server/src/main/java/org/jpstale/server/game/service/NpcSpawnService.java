package org.jpstale.server.game.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.MapNpc;
import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.dao.gamedb.mapper.MapNpcMapper;
import org.jpstale.dao.gamedb.mapper.NpcListMapper;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Npc;
import org.jpstale.server.game.network.PlayerSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC 加载服务（静态站桩）。
 *
 * 启动时从 gamedb.mapnpc + gamedb.npclist 加载全量 NPC 到内存，按地图（stage）索引。
 * 坐标域与服务端实体一致（raw 整数，不 /256）：x=x, y=y, z=z（DB 已按数据层翻转 -z）；angle=raw/4096*2π（弧度）。
 * NPC 纯展示，不进 tick 移动/AI。
 */
@Slf4j
@Component
public class NpcSpawnService {

    private static final double ANGLE_CIRCLE = 4096.0;

    @Autowired
    private NpcListMapper npcListMapper;

    @Autowired
    private MapNpcMapper mapNpcMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    /** mapId → 该图 NPC 列表（委托 EntityRegistry） */

    @Autowired
    private EntityRegistry entityRegistry;

    @PostConstruct
    public void init() {
        loadNpcs();
    }

    private void loadNpcs() {
        Map<Integer, NpcList> defs = new HashMap<>();
        for (NpcList t : npcListMapper.selectList(null)) {
            defs.put(t.getId(), t);
        }

        int count = 0;
        for (MapNpc mn : mapNpcMapper.selectList(null)) {
            if (mn.getEnabled() != null && mn.getEnabled() == 0) continue;       // 未启用
            // onlygm 的 NPC **照常生成**（原版语义是"只有 GM 能交互"，见 Npc.gmOnly 注释）
            NpcList def = mn.getIdNpc() == null ? null : defs.get(mn.getIdNpc());
            if (def == null) continue;

            Npc npc = new Npc();
            npc.setNpcId(def.getId());
            npc.setEventType(def.getEventType() == null ? 0 : def.getEventType());   // 服务判据（见 NpcCraftTable）
            npc.setTeleportId(def.getTeleportId() == null ? 0 : def.getTeleportId()); // 传送目的地事件码（见 TravelService.NPC_TELEPORTS）
            npc.setNameKey(def.getName());
            npc.setModelFile(normalizeModelPath(def.getGameFile()));
            npc.setX(mn.getX() == null ? 0 : mn.getX());
            npc.setY(mn.getY() == null ? 0 : mn.getY());
            npc.setZ(mn.getZ() == null ? 0 : mn.getZ());
            // 角度：DB 原始 DX 角 → GL 弧度需 yaw 镜像补偿。
            // 原版客户端渲染：angle.y = (-angle.y + ANGLE_180) & ANGCLIP，等价 GL 弧度 = π - DX 弧度。
            double angleDx = (mn.getAngle() == null ? 0 : mn.getAngle()) / ANGLE_CIRCLE * Math.PI * 2;
            double angleGl = Math.PI - angleDx;
            angleGl = (angleGl % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
            npc.setAngle(angleGl);
            npc.setMapId(mn.getStage() == null ? -1 : mn.getStage());
            npc.setGmOnly(mn.getOnlyGm() != null && mn.getOnlyGm() != 0);

            entityRegistry.register(npc);
            count++;
        }
        log.info("NpcSpawnService initialized: {} npcs", count);
    }

    /**
     * 按图取 NPC —— **只给"这张图自己"的逻辑用**（地图上限、世界地图）。
     *
     * ⚠ 可见性不要用它：地图是人为切分的，坐标才是空间真身。
     * 玩家 AOI 走坐标（`AOIManager.getNearbyPlayers`），NPC 也必须一样 ——
     * 否则站在村庄门口就看不到门外那个 NPC（见 {@link #allNpcs()}）。
     */
    public List<Npc> getNpcsByMap(int mapId) {
        return entityRegistry.npcsByMap(mapId);
    }

    /**
     * **全部** NPC（只读遍历用，不复制、不分配）。
     *
     * 与 `MonsterSpawnService.allMonsters()` 同一个理由：地图边界是人为切分的，
     * "谁在我附近"只能按坐标判（用户 2026-09-16 报的正是"跨边界的东西看不见"）。
     */
    public java.util.Collection<Npc> allNpcs() {
        return entityRegistry.allNpcs();
    }

    /** 按**运行时实体 id** 取 NPC（不存在返回 null）。交互校验用（定义 id 不下发客户端）。 */
    public Npc findById(long entityId) {
        return entityRegistry.findNpc(entityId);
    }

    /**
     * 按运行时实体 id 取**本图**的 NPC 实例；不在本图/不存在返回 null。
     *
     * 客户端只持有实体 id（`S2C_NpcAppear` 下发，定义 id 不下发）；位置以服务端为准，
     * 客户端报什么都不影响判定。放在这里而不是共享层：它依赖运行时实体 `Npc` 与刷怪状态。
     */
    public Npc findInMap(int mapId, long entityId) {
        Npc n = findById(entityId);
        return (n != null && n.getMapId() == mapId) ? n : null;
    }

    // ------------------------------------------------------------------
    // 交互校验（单一实现：NpcShopHandler 与 TravelService 共用 —— AGENTS #15）
    // ------------------------------------------------------------------

    /**
     * NPC 交互距离上限（世界单位）。原版这条校验在**客户端**（点谁是谁），服务端没有；
     * 我们加它是为了防"远程开面板"，取一个明显够用又不过分的值（玩家与 NPC 正常面对面）。
     */
    public static final double NPC_INTERACT_RANGE = 96.0d;

    /**
     * 交互校验：本图有该实体 + 非 GM 专用 + 距离够。任一不过 → 回明确的错误 key 并返回 null。
     * 商店/传送两个入口共用（商家判定等各自专属的门由调用方自己做）。
     */
    public Npc resolveInteractable(PlayerSession session, long entityId, String outOfRangeKey) {
        PlayerEntity ent = session.getEntity();
        if (ent == null || ent.getMapId() < 0) {
            return null;
        }
        Npc npc = findInMap(ent.getMapId(), entityId);
        if (npc == null) {
            log.warn("[Npc] 交互被拒：entity={} 在 mapId={} 上没有实例（可疑）", entityId, ent.getMapId());
            sendErrorKey(session, "shop.notHere");
            return null;
        }
        if (!isGm(session)) {
            if (npc.isGmOnly()) {
                // 原版：非 GM 点 onlygm 的 NPC → 提示 "> Only for Admins!"（NPC 本身可见）
                log.info("[Npc] 交互被拒：npc={} 是 GM 专用", npc.getNpcId());
                sendErrorKey(session, "npc.gmOnly");
                return null;
            }
            double dx = npc.getX() - ent.getX();
            double dz = npc.getZ() - ent.getZ();
            if (dx * dx + dz * dz > NPC_INTERACT_RANGE * NPC_INTERACT_RANGE) {
                log.warn("[Npc] 交互被拒：npc={} 距离 {} 超过 {}（可疑）",
                        npc.getNpcId(), Math.sqrt(dx * dx + dz * dz), NPC_INTERACT_RANGE);
                sendErrorKey(session, outOfRangeKey);
                return null;
            }
        }
        return npc;
    }

    /** GM 判定：`UserInfo.gamemastertype != 0 && gamemasterlevel > 0`（EU 用 `GameMasterType/Level`）。 */
    public boolean isGm(PlayerSession session) {
        Long accountId = session.getAccountId();
        if (accountId == null) {
            return false;
        }
        var u = userInfoMapper.selectById(accountId);
        return u != null && u.getGameMasterType() != null && u.getGameMasterType() != 0
                && u.getGameMasterLevel() != null && u.getGameMasterLevel() > 0;
    }

    private void sendErrorKey(PlayerSession session, String key) {
        session.send(org.jpstale.server.proto.base.ServerMessage.newBuilder()
                .setError(org.jpstale.server.proto.base.S2C_Error.newBuilder()
                        .setErrorCode(org.jpstale.server.proto.base.CommonProto.ErrorCode.UNKNOWN_ERROR)
                        .setKey(key)
                        .build())
                .build());
    }

    /**
     * 规范化 DB gamefile → 客户端 .inx 资源路径（与 MonsterSpawnService 同规则）：
     * 反斜杠→斜杠、转小写（Linux 大小写敏感）、去扩展名后统一补 .inx。
     * 例：char\npc\TN-005\TN-005.ini → char/npc/tn-005/tn-005.inx
     */
    private static String normalizeModelPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace('\\', '/').trim().toLowerCase();
        int slash = s.lastIndexOf('/');
        String dir = slash >= 0 ? s.substring(0, slash) : "";
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String base = dir.isEmpty() ? name : dir + "/" + name;
        return base + ".inx";
    }
}
