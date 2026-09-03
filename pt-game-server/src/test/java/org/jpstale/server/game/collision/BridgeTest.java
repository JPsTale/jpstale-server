package org.jpstale.server.game.collision;

import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class BridgeTest {

    private static CollisionMesh mesh;

    @BeforeClass
    public static void load() {
        String root = System.getProperty("pt.smd.root", "E:\\JPsTale\\client");
        File f = new File(root, "field/" + FieldMap.FIELD_2.smd);
        Assume.assumeTrue("smd not found: " + f, f.exists());
        MapMesh m = MapMesh.load(f);
        Assume.assumeNotNull(m);
        mesh = m.getCollision();
    }

    // 桥前缘（fore-1, mapId=2）。world 坐标 = (rawX/256, rawY/256, -rawZ/256)，北正，
    // 与 jpstale-web selfPos 同域：起点 (4891.50, 507.45, 6687.25)。
    // 探查确认桥在起点 -z 方向（z 减小：507→引桥坡 508~515→桥面 519.58，抬升 12.13 > STEP_HEIGHT=10），
    // 故朝 -z 走（angle=π）。临界步长 ≈5.504：5.33 单步落在引桥坡（抬升 <10）上桥，
    // 5.68 单步叉开坡面跌回桥下。
    private static final double FX = 4891.50;
    private static final double FY = 507.45;
    private static final double FZ = 6687.25;
    private static final double ANGLE = Math.PI; // -z（桥方向）

    /** 从桥前地面走一步 5.33，落到引桥坡（approach，y≈509.18），返回其位置。 */
    private static CollisionMesh.MoveResult approach() {
        CollisionMesh.MoveResult r = mesh.checkNextMove(FX, FY, FZ, ANGLE, 5.33, 11);
        assertFalse("引桥坡应可达", r.collision);
        return r;
    }

    @Test
    public void step533ClimbsBridge() {
        CollisionMesh.MoveResult a = approach();
        CollisionMesh.MoveResult r = mesh.checkNextMove(a.x, a.y, a.z, ANGLE, 5.33, 11);
        assertFalse("5.33 应上桥", r.collision);
        assertTrue("5.33 应抬升上桥，实际 y=" + r.y, r.y > a.y + 5);
    }

    @Test
    public void step568CcdDoesNotTunnel() {
        CollisionMesh.MoveResult a = approach();
        CollisionMesh.MoveResult r = mesh.checkNextMoveCcd(a.x, a.y, a.z, ANGLE, 5.68, 11);
        assertTrue("5.68 不应穿透桥，实际 y=" + r.y, r.y >= a.y - 1);
    }

    @Test
    public void bigStep16CcdDoesNotTunnel() {
        CollisionMesh.MoveResult a = approach();
        CollisionMesh.MoveResult r = mesh.checkNextMoveCcd(a.x, a.y, a.z, ANGLE, 16.0, 11);
        assertTrue("大步 16 不应穿透桥，实际 y=" + r.y, r.y >= a.y - 1);
    }

    // 基线：证明单步 checkNextMove(5.68) 确实穿桥（从引桥坡跌回桥下），CCD 是必要的。
    @Test
    public void baselineStep568RawTunnels() {
        CollisionMesh.MoveResult a = approach();
        CollisionMesh.MoveResult r = mesh.checkNextMove(a.x, a.y, a.z, ANGLE, 5.68, 11);
        assertTrue("5.68 单步应穿桥（基线），实际 y=" + r.y, r.y < a.y - 1);
    }
}
