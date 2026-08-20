package org.jpstale.server.game.model;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.LittleEndien;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.assets.plugins.smd.stage.Stage;
import org.jpstale.assets.plugins.smd.stage.StageFace;
import org.jpstale.assets.plugins.smd.stage.StageVertex;
import org.jpstale.assets.utils.SceneBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

/**
 * 地图网格（.smd），一次性读入内存长期驻留。
 * <p>
 * 复用 GridMesh 九宫格分块做碰撞/地形判定加速（坐标处只用九宫格内的三角形做点-三角形测试）。
 * 坐标与 AABB 一致：.smd 顶点为翻转坐标（x=-raw, z=-raw，÷256），
 * 出生点/门坐标（原始）查询时需翻转（-x, -z）。
 */
@Slf4j
public class MapMesh {

    /** jME3 碰撞网格（buildCollisionMesh 产出，已过滤非碰撞面） */
    private final Mesh mesh;
    /** 九宫格分块索引 */
    private final GridMesh grid;

    /** 顶点数据（拍平 x,y,z，供前端 mesh 绘制） */
    private final float[] vertices;
    /** 三角形索引（每面 3 索引，供前端 mesh 绘制） */
    private final int[] indices;
    private final int faceCount;

    private final float minX, maxX, minZ, maxZ;

    private MapMesh(Mesh mesh, GridMesh grid, float[] vertices, int[] indices, int faceCount,
                    float minX, float maxX, float minZ, float maxZ) {
        this.mesh = mesh;
        this.grid = grid;
        this.vertices = vertices;
        this.indices = indices;
        this.faceCount = faceCount;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /**
     * 从 .smd 文件加载地图网格（完整解析）。
     */
    public static MapMesh load(File file) {
        if (!file.exists()) {
            return null;
        }
        try (LittleEndien in = new LittleEndien(new FileInputStream(file))) {
            Stage stage = new Stage();
            stage.loadFile(in);

            if (stage.nVertex <= 0 || stage.nFace <= 0) {
                return null;
            }

            Mesh mesh = buildCollisionMeshSafe(stage);
            if (mesh == null) {
                return null;
            }

            // 顶点/索引（翻转坐标已由 StageVertex 处理：x=-raw, z=-raw）
            FloatBuffer fb = (FloatBuffer) mesh.getBuffer(Type.Position).getData();
            IntBuffer ib = (IntBuffer) mesh.getBuffer(Type.Index).getData();
            int vCount = fb.limit() / 3;
            float[] verts = new float[vCount * 3];
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < vCount; i++) {
                float x = fb.get(i * 3);
                float z = fb.get(i * 3 + 2);
                verts[i * 3] = x;
                verts[i * 3 + 1] = fb.get(i * 3 + 1);
                verts[i * 3 + 2] = z;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
            int fCount = ib.limit() / 3;
            int[] idx = new int[fCount * 3];
            for (int i = 0; i < fCount * 3; i++) {
                idx[i] = ib.get(i);
            }

            GridMesh grid = new GridMesh(mesh);
            return new MapMesh(mesh, grid, verts, idx, fCount, minX, maxX, minZ, maxZ);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to load map mesh: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 构建碰撞网格（只保留 MeshState &amp; 0x0001 的碰撞面）。
     * 非碰撞面（建筑装饰等）不应参与地形判定，不退回全量面。
     */
    private static Mesh buildCollisionMeshSafe(Stage stage) {
        return SceneBuilder.buildCollisionMesh(stage);
    }

    /**
     * 地形高度判定（对齐 EU GetHeight：坐标处有地形面返回高度，否则 0）。
     * 入参为对齐顶点数组的坐标：.smd 顶点 X 分量 = -世界Z、Z 分量 = -世界X（已交换），
     * 故对世界坐标 (rawX, rawZ) 需传 (-rawZ, -rawX)。用 GridMesh 九宫格加速。
     */
    public float getHeight(float x, float z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return 0;
        }
        FloatBuffer fb = (FloatBuffer) mesh.getBuffer(Type.Position).getData();
        IntBuffer ib = (IntBuffer) mesh.getBuffer(Type.Index).getData();

        com.jme3.math.Vector2f p = grid.getAreaPosition(x, z);
        if (p == null) {
            return 0;
        }
        ArrayList<Integer> faces = grid.getFaceList((int) p.x, (int) p.y);
        if (faces == null) {
            return 0;
        }
        float height = 0;
        for (Integer fi : faces) {
            int i0 = ib.get(fi * 3);
            int i1 = ib.get(fi * 3 + 1);
            int i2 = ib.get(fi * 3 + 2);
            float ax = fb.get(i0 * 3), ay = fb.get(i0 * 3 + 1), az = fb.get(i0 * 3 + 2);
            float bx = fb.get(i1 * 3), by = fb.get(i1 * 3 + 1), bz = fb.get(i1 * 3 + 2);
            float cx = fb.get(i2 * 3), cy = fb.get(i2 * 3 + 1), cz = fb.get(i2 * 3 + 2);
            if (pointInTriangle(x, z, ax, az, bx, bz, cx, cz)) {
                float h = barycentricHeight(x, z, ax, ay, az, bx, by, bz, cx, cy, cz);
                if (h > height) {
                    height = h;
                }
            }
        }
        return height;
    }

    /** 判断点 (px,pz) 是否在三角形 (a,b,c) 内（xz 平面投影） */
    private boolean pointInTriangle(float px, float pz,
                                    float ax, float az, float bx, float bz, float cx, float cz) {
        float d1 = sign(px, pz, ax, az, bx, bz);
        float d2 = sign(px, pz, bx, bz, cx, cz);
        float d3 = sign(px, pz, cx, cz, ax, az);
        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    private float sign(float px, float pz, float ax, float az, float bx, float bz) {
        return (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
    }

    /** 重心坐标插值高度 */
    private float barycentricHeight(float px, float pz,
                                    float ax, float ay, float az,
                                    float bx, float by, float bz,
                                    float cx, float cy, float cz) {
        float det = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
        if (Math.abs(det) < 1e-6) {
            return ay;
        }
        float l1 = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / det;
        float l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / det;
        float l3 = 1 - l1 - l2;
        return l1 * ay + l2 * by + l3 * cy;
    }

    public float getMinX() { return minX; }
    public float getMaxX() { return maxX; }
    public float getMinZ() { return minZ; }
    public float getMaxZ() { return maxZ; }
    public int getFaceCount() { return faceCount; }
    public int getVertexCount() { return vertices.length / 3; }

    /** 顶点数据（拍平 x,y,z） */
    public float[] getVertices() { return vertices; }

    /** 三角形索引 */
    public int[] getIndices() { return indices; }

    /** 是否含该坐标（AABB 内） */
    public boolean contains(float x, float z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
