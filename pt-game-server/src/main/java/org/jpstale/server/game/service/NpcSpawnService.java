package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.MapNpc;
import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.dao.gamedb.mapper.MapNpcMapper;
import org.jpstale.dao.gamedb.mapper.NpcListMapper;
import org.jpstale.server.game.model.Npc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 加载服务（静态站桩）。
 *
 * 启动时从 gamedb.mapnpc + gamedb.npclist 加载全量 NPC 到内存，按地图（stage）索引。
 * 坐标域与服务端实体一致（raw 整数，不 /256）：x=x, y=y, z=-z；angle=raw/4096*2π（弧度）。
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

    /** mapId → 该图 NPC 列表 */
    private final Map<Integer, List<Npc>> npcsByMap = new ConcurrentHashMap<>();

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
            if (mn.getOnlyGm() != null && mn.getOnlyGm() != 0) continue;          // 仅 GM 可见
            NpcList def = mn.getIdNpc() == null ? null : defs.get(mn.getIdNpc());
            if (def == null) continue;

            Npc npc = new Npc();
            npc.setNpcId(def.getId());
            npc.setNameKey(def.getName());
            npc.setModelFile(normalizeModelPath(def.getGameFile()));
            npc.setX(mn.getX() == null ? 0 : mn.getX());
            npc.setY(mn.getY() == null ? 0 : mn.getY());
            npc.setZ(-(mn.getZ() == null ? 0 : mn.getZ()));
            npc.setAngle(((mn.getAngle() == null ? 0 : mn.getAngle()) / ANGLE_CIRCLE) * Math.PI * 2);
            npc.setMapId(mn.getStage() == null ? -1 : mn.getStage());

            npcsByMap.computeIfAbsent(npc.getMapId(), k -> new ArrayList<>()).add(npc);
            count++;
        }
        log.info("NpcSpawnService initialized: {} npcs on {} maps", count, npcsByMap.size());
    }

    public List<Npc> getNpcsByMap(int mapId) {
        return npcsByMap.getOrDefault(mapId, List.of());
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
