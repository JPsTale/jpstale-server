package org.jpstale.server.game.model;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.assets.smd.SmdMapData;
import org.jpstale.assets.smd.SmdMapLoader;
import org.jpstale.assets.smd.CollisionMesh;

import java.io.File;
import java.io.IOException;

/**
 * 地图网格（.smd）—— SmdMapLoader 加载，world double 运算域。
 * <p>
 * 坐标域：world (x, y, z) = (rawX/256, rawY/256, -rawZ/256)，北正，double。
 * raw int 只在 {@link SmdMapData} 读取层，构建时即转 world。
 */
@Slf4j
public class MapMesh {

    /** 原始 SMD 数据（raw int 读取层） */
    private final SmdMapData data;
    /** world double 碰撞网格（meshState &amp; 1 碰撞面） */
    private final CollisionMesh collision;

    /** world AABB */
    private final double minX, maxX, minZ, maxZ;

    private MapMesh(SmdMapData data) {
        this.data = data;
        this.collision = CollisionMesh.fromSmd(data);
        // raw AABB → world：x=raw/256 同号；z=-raw/256（范围取反）
        this.minX = data.minX / 256.0;
        this.maxX = data.maxX / 256.0;
        this.minZ = -(double) data.maxZ / 256.0;
        this.maxZ = -(double) data.minZ / 256.0;
    }

    /** 从 .smd 文件加载（world double）。 */
    public static MapMesh load(File file) {
        if (!file.exists()) {
            log.info("MapMesh could not be loaded for {}", file.getAbsolutePath());
            return null;
        }
        try {
            SmdMapData d = SmdMapLoader.load(file.toPath());
            if (d.nVertex <= 0 || d.nFace <= 0) {
                log.info("MapMesh could not be loaded for {}", file.getAbsolutePath());
                return null;
            }
            return new MapMesh(d);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to load map mesh: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /** 从已解析数据构建。 */
    public static MapMesh fromSmdData(SmdMapData data) {
        return new MapMesh(data);
    }

    /** 原始 SMD 数据 */
    public SmdMapData getSmdData() {
        return data;
    }

    /** world double 碰撞网格 */
    public CollisionMesh getCollision() {
        return collision;
    }

    /** 地形高度（world double）：x,z 处可站立地面最高高度，无则 0。 */
    public double getHeight(double x, double z) {
        Double h = collision.getFloorHeight(x, z, Double.MAX_VALUE);
        return h == null ? 0.0 : h;
    }

    /** world 碰撞面顶点（x,y,z 每顶点 3 double） */
    public double[] getVertices() {
        return data.vertsWorldDouble();
    }

    /** world 碰撞面索引（每面 3） */
    public int[] getIndices() {
        return data.solidFaceIndices();
    }

    public int getFaceCount() {
        return data.solidFaceIndices().length / 3;
    }

    public double getMinX() { return minX; }
    public double getMaxX() { return maxX; }
    public double getMinZ() { return minZ; }
    public double getMaxZ() { return maxZ; }

    /** 是否含该坐标（world AABB 内） */
    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
