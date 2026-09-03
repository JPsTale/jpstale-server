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
        mesh = CollisionMesh.fromMapMesh(m);
    }

    // 桥前缘（fore-1, mapId=2），碰撞帧 F = 服务端世界坐标 (worldX, worldY, worldZ)。
    // 参考 docs/movement-speed-analysis.md §7：客户端 web 起点 (4891.50, 507.45, 6687.25)，
    // 朝 -web z（raw +z）北移。服务端 worldZ = -webZ，故起点 (4891.50, 507.45, -6687.25)，
    // 朝 -web z = +world z（= angle 0）。桥前地面 y=507.45，桥面顶 y=519.58（抬升 12.13 > STEP_HEIGHT=10）。
    // 临界步长 ≈5.504：5.33 单步落在引桥坡（抬升 <10）上桥，5.68 单步叉开坡面跌回桥下。
    private static final float FX = 4891.50f;
    private static final float FY = 507.45f;
    private static final float FZ = -6687.25f;
    private static final double ANGLE = 0; // +world z（北）

    /** 从桥前地面走一步 5.33，落到引桥坡（approach，y≈509.18），返回其位置。 */
    private static CollisionMesh.MoveResult approach() {
        CollisionMesh.MoveResult r = mesh.checkNextMove(FX, FY, FZ, ANGLE, 1365.0 / 256.0, 11);
        assertFalse("引桥坡应可达", r.collision);
        return r;
    }

    @Test
    public void step533ClimbsBridge() {
        CollisionMesh.MoveResult a = approach();
        CollisionMesh.MoveResult r = mesh.checkNextMove(a.x, a.y, a.z, ANGLE, 1365.0 / 256.0, 11);
        assertFalse("5.33 应上桥", r.collision);
        assertTrue("5.33 应抬升上桥，实际 y=" + r.y, r.y > a.y + 5);
    }

    @Test
    public void step568CcdDoesNotTunnel() {
        CollisionMesh.MoveResult a = approach();
        CollisionMesh.MoveResult r = mesh.checkNextMoveCcd(a.x, a.y, a.z, ANGLE, 1455.0 / 256.0, 11);
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
        CollisionMesh.MoveResult r = mesh.checkNextMove(a.x, a.y, a.z, ANGLE, 1455.0 / 256.0, 11);
        assertTrue("5.68 单步应穿桥（基线），实际 y=" + r.y, r.y < a.y - 1);
    }
}
