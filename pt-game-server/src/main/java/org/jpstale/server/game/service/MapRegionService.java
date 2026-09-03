package org.jpstale.server.game.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 地图区域服务。
 * <p>
 * 以 C++ InitField() 顺序为权威（FieldMap 枚举硬编码 44 张地图），
 * 启动时用 {@link MapMesh}（SmdMapLoader）加载 .smd 碰撞网格。
 * <p>
 * 坐标域：world (x, y, z) = (rawX/256, rawY/256, -rawZ/256)，北正，double。
 * 与 FieldMap/DB spawn/玩家位置/碰撞全部同域，无任何翻转/交换补丁。
 */
@Slf4j
@Service
public class MapRegionService {

    /** 门触发距离（world 单位，与 AOI CONNECT 一致） */
    public static final int GATE_CONNECT_DIST = 1086;
    /** 门最大轴向距离（world 单位） */
    private static final int GATE_MAX_AXIS = 16384;

    @Value("${pt.field.smd-root:/data/PristonTale/exm/run}")
    private String smdRoot;

    /** mapId -> 地图网格（一次性读入长期驻留） */
    private final MapMesh[] meshes = new MapMesh[FieldMap.values().length];

    @PostConstruct
    public void init() {
        int loaded = 0;
        for (FieldMap map : FieldMap.values()) {
            MapMesh mesh = MapMesh.load(new File(smdRoot, "field/" + map.smd));
            meshes[map.ordinal()] = mesh;
            if (mesh != null) loaded++;
        }
        log.info("MapRegionService loaded {} / {} meshes from smd", loaded, meshes.length);
    }

    private MapMesh mesh(int mapId) {
        if (mapId < 0 || mapId >= meshes.length) return null;
        return meshes[mapId];
    }

    /**
     * 获取地图 world AABB [xMin, xMax, zMin, zMax]，无则 null。
     */
    public double[] getAabb(int mapId) {
        MapMesh m = mesh(mapId);
        if (m == null) return null;
        return new double[]{m.getMinX(), m.getMaxX(), m.getMinZ(), m.getMaxZ()};
    }

    /**
     * 地图是否存在（枚举范围内且有网格）
     */
    public boolean isMap(int mapId) {
        return mesh(mapId) != null;
    }

    /**
     * AABB 反查 + GetHeight 精确判定（对齐原版 FindStageField）。
     *
     * @param currentMapId 玩家当前地图
     * @return 判定结果地图 id（可能等于 currentMapId）
     */
    public int findMapPrecise(int currentMapId, double x, double z) {
        // 1. AABB 粗筛
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        for (int i = 0; i < meshes.length; i++) {
            MapMesh m = meshes[i];
            if (m != null && x >= m.getMinX() && x <= m.getMaxX() && z >= m.getMinZ() && z <= m.getMaxZ()) {
                hits.add(i);
            }
        }
        // 2. 唯一命中
        if (hits.size() == 1) {
            return hits.get(0);
        }
        // 3. 多命中（重叠交界）→ GetHeight 精确判定（world 直查）
        if (hits.size() > 1) {
            for (Integer mapId : hits) {
                MapMesh m = meshes[mapId];
                if (m != null && m.getHeight(x, z) > 0) {
                    return mapId;
                }
            }
        }
        // 4. 无命中或歧义：保持当前图
        return currentMapId >= 0 && currentMapId < meshes.length && meshes[currentMapId] != null ? currentMapId : -1;
    }

    /**
     * 地形高度判定（world double 直查，与碰撞同域）：坐标处有可站立地面返回高度，否则 0。
     */
    public double getHeight(int mapId, double x, double z) {
        MapMesh m = mesh(mapId);
        if (m == null) return 0;
        return m.getHeight(x, z);
    }

    /**
     * 当前图 + 相邻图（由 FieldGate 门目标推导），供前端绘制 mesh 背景。
     */
    public int[] getNeighborMaps(int mapId) {
        if (mapId < 0 || mapId >= FieldMap.values().length) {
            return new int[0];
        }
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        set.add(mapId);
        for (FieldMap.Gate gate : FieldMap.values()[mapId].gates) {
            if (gate.to >= 0 && gate.to < FieldMap.values().length) {
                set.add(gate.to);
            }
        }
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 获取地图网格，无则返回 null
     */
    public MapMesh getMesh(int mapId) {
        return mesh(mapId);
    }

    /**
     * 门距离判定（world 坐标，同域直查）。
     *
     * @return 若触发门，返回目标地图 id；否则返回 -1
     */
    public int findFieldGate(int mapId, double x, double z) {
        if (mapId < 0 || mapId >= FieldMap.values().length) {
            return -1;
        }
        FieldMap map = FieldMap.values()[mapId];
        for (FieldMap.Gate gate : map.gates) {
            double dx = x - gate.x;
            double dz = z - gate.z;
            if (Math.abs(dx) > GATE_MAX_AXIS || Math.abs(dz) > GATE_MAX_AXIS) {
                continue;
            }
            double dist = dx * dx + dz * dz;
            if (dist < (double) GATE_CONNECT_DIST * GATE_CONNECT_DIST) {
                return gate.to;
            }
        }
        return -1;
    }

    /**
     * 取地图出生点：离给定坐标最近的出生点；无出生点用地图中心。
     *
     * @return [x, z]，地图不存在返回 null
     */
    public int[] getStartPoint(int mapId, double x, double z) {
        if (mapId < 0 || mapId >= FieldMap.values().length) {
            return null;
        }
        FieldMap map = FieldMap.values()[mapId];
        if (map.startPoints == null || map.startPoints.length == 0) {
            return map.center;
        }
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < map.startPoints.length; i++) {
            double dx = map.startPoints[i][0] - x;
            double dz = map.startPoints[i][1] - z;
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return map.startPoints[best];
    }

    /**
     * 地图数量
     */
    public int size() {
        return FieldMap.values().length;
    }
}
