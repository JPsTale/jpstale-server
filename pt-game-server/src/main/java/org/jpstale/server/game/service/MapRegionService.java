package org.jpstale.server.game.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 地图区域服务。
 * <p>
 * 以 C++ InitField() 顺序为权威（FieldMap 枚举硬编码 44 张地图），
 * 启动时从 .smd 碰撞网格文件头部读取 RECT（地图 AABB）。
 * <p>
 * 提供：
 * <ul>
 *   <li>findMap(x, z)：AABB 反查玩家所在地图（对齐 EU FindStageField）</li>
 *   <li>findFieldGate(mapId, x, z)：门距离判定（对齐客户端 PlayNearGateField，阈值 DIST_TRANSLEVEL_CONNECT=1086）</li>
 * </ul>
 */
@Slf4j
@Service
public class MapRegionService {

    /** 门触发距离（√≈1086 单位，与 AOI CONNECT 一致） */
    public static final int GATE_CONNECT_DIST = 1086;
    /** 门最大轴向距离（EU PlayNearGateField: abs(dx)<16384） */
    private static final int GATE_MAX_AXIS = 16384;

    /** .smd 头部 RECT 的字节偏移（SmdFileHeader 556 + StageArea 262144 + 25 int = 262800） */
    private static final int SMD_RECT_OFFSET = 262800;

    @Value("${pt.field.smd-root:/data/PristonTale/exm/run}")
    private String smdRoot;

    /** mapId -> AABB [xMin, xMax, zMin, zMax] */
    private final int[][] aabbs = new int[FieldMap.values().length][];

    /** mapId -> 地图网格（一次性读入长期驻留） */
    private final MapMesh[] meshes = new MapMesh[FieldMap.values().length];

    @PostConstruct
    public void init() {
        for (FieldMap map : FieldMap.values()) {
            int[] aabb = readSmdRect(smdRoot, map.smd);
            aabbs[map.ordinal()] = aabb;
            if (aabb == null) {
                log.warn("Field {} ({}) has no AABB, smd missing", map.ordinal(), map.smd);
            }
            // 完整解析网格（一次读入驻留），供 GetHeight 精确判定与前端 mesh 绘制
            meshes[map.ordinal()] = MapMesh.load(new File(smdRoot, "field/" + map.smd));
        }
        int loaded = 0;
        int meshLoaded = 0;
        for (int[] a : aabbs) {
            if (a != null) loaded++;
        }
        for (MapMesh m : meshes) {
            if (m != null) meshLoaded++;
        }
        log.info("MapRegionService loaded {} / {} field AABBs, {} / {} meshes from smd",
            loaded, aabbs.length, meshLoaded, meshes.length);
    }

    /**
     * 从 .smd 文件头部读取地图 RECT（÷256 得世界坐标 AABB）。
     *
     * @return [xMin, xMax, zMin, zMax]，文件缺失/损坏返回 null
     */
    private int[] readSmdRect(String root, String smdRel) {
        // C++ SetName 的文件名相对 field\ 目录，补上前缀
        File file = new File(root, "field/" + smdRel);
        if (!file.exists()) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // .smd 为小端存储，RECT 在偏移 262800 处的 4 个 int
            raf.seek(SMD_RECT_OFFSET);
            byte[] buf = new byte[16];
            raf.readFully(buf);
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int left = bb.getInt();
            int top = bb.getInt();
            int right = bb.getInt();
            int bottom = bb.getInt();
            return new int[]{left / 256, right / 256, top / 256, bottom / 256};
        } catch (IOException e) {
            log.warn("Failed to read smd RECT: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 获取地图 AABB，无则返回 null
     */
    public int[] getAabb(int mapId) {
        if (mapId < 0 || mapId >= aabbs.length) {
            return null;
        }
        return aabbs[mapId];
    }

    /**
     * 地图是否存在（枚举范围内）
     */
    public boolean isMap(int mapId) {
        return mapId >= 0 && mapId < FieldMap.values().length;
    }

    /**
     * AABB 反查 + GetHeight 精确判定（对齐原版 FindStageField）。
     * <p>
     * 流程：
     * <ol>
     *   <li>AABB 粗筛出所有命中的地图</li>
     *   <li>唯一命中 → 直接返回（与当前图不同则触发跨图）</li>
     *   <li>多图命中（交界重叠）→ 用 GetHeight（地形面高度 &gt;0）精确判定；
     *       仍歧义则保持当前图，避免抖动</li>
     * </ol>
     *
     * @param currentMapId 玩家当前地图
     * @return 判定结果地图 id（可能等于 currentMapId）
     */
    public int findMapPrecise(int currentMapId, float x, float z) {
        // 1. AABB 粗筛
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        for (int i = 0; i < aabbs.length; i++) {
            int[] a = aabbs[i];
            if (a != null && x >= a[0] && x <= a[1] && z >= a[2] && z <= a[3]) {
                hits.add(i);
            }
        }

        // 2. 唯一命中
        if (hits.size() == 1) {
            return hits.get(0);
        }

        // 3. 多命中（重叠交界）→ GetHeight 精确判定
        //    对齐原版 FindStageField：按 StageArea 顺序（mapId 升序）遍历，
        //    第一个 GetHeight>0 的地图即玩家实际落足点（当前图地形优先）。
        if (hits.size() > 1) {
            // .smd 顶点坐标已交换（顶点 X 分量 = -世界Z，顶点 Z 分量 = -世界X）：
            // 查询需传 (-z, -x) 才能对齐顶点数组
            float fx = -z;
            float fz = -x;
            for (Integer mapId : hits) {
                MapMesh mesh = meshes[mapId];
                if (mesh == null) continue;
                if (mesh.getHeight(fx, fz) > 0) {
                    return mapId;
                }
            }
        }

        // 4. 无命中或歧义：保持当前图（稳定不抖动）
        return currentMapId >= 0 && currentMapId < aabbs.length ? currentMapId : -1;
    }

    /**
     * 地形高度判定：坐标处在该图地形面上返回高度，否则 0。
     * 入参为原始世界坐标（内部翻转+交换，对齐 .smd 顶点）。
     */
    public float getHeight(int mapId, float x, float z) {
        if (mapId < 0 || mapId >= meshes.length || meshes[mapId] == null) {
            return 0;
        }
        return meshes[mapId].getHeight(-z, -x);
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
     * 获取地图网格（供前端 mesh 绘制），无则返回 null
     */
    public MapMesh getMesh(int mapId) {
        if (mapId < 0 || mapId >= meshes.length) {
            return null;
        }
        return meshes[mapId];
    }

    /**
     * 门距离判定：检查玩家是否进入当前地图某个门（对齐 EU PlayNearGateField）。
     *
     * @return 若触发门，返回目标地图 id；否则返回 -1
     */
    public int findFieldGate(int mapId, float x, float z) {
        if (mapId < 0 || mapId >= FieldMap.values().length) {
            return -1;
        }
        FieldMap map = FieldMap.values()[mapId];
        for (FieldMap.Gate gate : map.gates) {
            int dx = (int) x - gate.x;
            int dz = (int) z - gate.z;
            if (Math.abs(dx) > GATE_MAX_AXIS || Math.abs(dz) > GATE_MAX_AXIS) {
                continue;
            }
            long dist = (long) dx * dx + (long) dz * dz;
            if (dist < (long) GATE_CONNECT_DIST * GATE_CONNECT_DIST) {
                return gate.to;
            }
        }
        return -1;
    }

    /**
     * 取地图出生点：离给定坐标最近的出生点；无出生点用地图中心（对齐 EU GetStartPoint）。
     *
     * @return [x, z]，地图不存在返回 null
     */
    public int[] getStartPoint(int mapId, float x, float z) {
        if (mapId < 0 || mapId >= FieldMap.values().length) {
            return null;
        }
        FieldMap map = FieldMap.values()[mapId];
        if (map.startPoints == null || map.startPoints.length == 0) {
            return map.center;
        }
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < map.startPoints.length; i++) {
            int[] p = map.startPoints[i];
            long dx = (long) p[0] - (long) x;
            long dz = (long) p[1] - (long) z;
            long dist = dx * dx + dz * dz;
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
