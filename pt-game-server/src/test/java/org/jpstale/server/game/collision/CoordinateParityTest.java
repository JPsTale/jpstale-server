package org.jpstale.server.game.collision;

import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * 坐标系对拍：碰撞帧 F 必须等于服务端世界坐标。
 * <p>
 * 判据：同一世界点 (wx,wz)，{@link CollisionMesh#getFloorHeight}（碰撞帧 F，传极大 currentY 关闭台阶过滤）
 * 必须与生产 {@code MapRegionService.getHeight} = {@code MapMesh.getHeight(-wz,-wx)} 返回同一地面高度。
 * 若帧映射错（x/z 交换或 z 取反），两函数查询的是不同几何点，高度几乎全部对不上。
 */
public class CoordinateParityTest {

    private static MapMesh mapMesh;
    private static CollisionMesh cm;

    @BeforeClass
    public static void load() {
        String root = System.getProperty("pt.smd.root", "E:\\JPsTale\\client");
        File f = new File(root, "field/" + FieldMap.FIELD_2.smd);
        Assume.assumeTrue("smd not found: " + f, f.exists());
        mapMesh = MapMesh.load(f);
        Assume.assumeNotNull(mapMesh);
        cm = CollisionMesh.fromMapMesh(mapMesh);
    }

    @Test
    public void floorHeightMatchesProductionGetHeight() {
        // 世界坐标 AABB（jME3 顶点 → 世界，同 fromMapMesh 映射）
        float[] verts = mapMesh.getVertices();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            float x = -verts[i + 2], z = -verts[i];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }

        Random rnd = new Random(7);
        int bothFound = 0, agree = 0;
        for (int i = 0; i < 2000; i++) {
            float wx = minX + rnd.nextFloat() * (maxX - minX);
            float wz = minZ + rnd.nextFloat() * (maxZ - minZ);
            float prod = mapMesh.getHeight(-wz, -wx);          // 生产 getHeight（MapRegionService 同款）
            Float col = cm.getFloorHeight(wx, wz, 100000f);    // 碰撞帧 F（极大 currentY 关台阶过滤）
            if (prod > 0 && col != null) {
                bothFound++;
                if (Math.abs(prod - col) < 0.5f) agree++;
            }
        }

        assertTrue("命中地形面样本太少: " + bothFound, bothFound > 500);
        double rate = agree / (double) bothFound;
        assertTrue("碰撞帧与生产 getHeight 高度不一致: agree=" + agree + "/" + bothFound + " (" + String.format("%.1f", rate * 100) + "%)",
                rate > 0.95);
    }
}
