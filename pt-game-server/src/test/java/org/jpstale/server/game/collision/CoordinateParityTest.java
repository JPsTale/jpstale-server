package org.jpstale.server.game.collision;

import org.jpstale.assets.smd.CollisionMesh;

import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * 坐标系对拍：MapMesh.getHeight（生产地形查询）必须与 CollisionMesh.getFloorHeight 同域一致。
 * <p>
 * 两者都是 world double（(rawX/256, rawY/256, -rawZ/256)，北正），对同一世界点 (wx,wz)
 * 应返回同一地面高度；若坐标域不一致（x/z 交换或 z 符号错），高度几乎全对不上。
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
        cm = mapMesh.getCollision();
    }

    @Test
    public void floorHeightMatchesProductionGetHeight() {
        // world AABB（MapMesh world double 顶点，同 getCollision 数据源）
        double[] verts = mapMesh.getVertices();
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            double x = verts[i], z = verts[i + 2];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }

        Random rnd = new Random(7);
        int bothFound = 0, agree = 0;
        for (int i = 0; i < 2000; i++) {
            double wx = minX + rnd.nextDouble() * (maxX - minX);
            double wz = minZ + rnd.nextDouble() * (maxZ - minZ);
            double prod = mapMesh.getHeight(wx, wz);        // 生产 getHeight（world 直查）
            Double col = cm.getFloorHeight(wx, wz, Double.MAX_VALUE); // 极大 currentY 关台阶过滤
            if (prod > 0 && col != null) {
                bothFound++;
                if (Math.abs(prod - col) < 0.5) agree++;
            }
        }

        assertTrue("命中地形面样本太少: " + bothFound, bothFound > 500);
        double rate = agree / (double) bothFound;
        assertTrue("getHeight 与碰撞帧高度不一致: agree=" + agree + "/" + bothFound + " (" + String.format("%.1f", rate * 100) + "%)",
                rate > 0.95);
    }
}
