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

    // 桥前缘（fore-1, mapId=2），碰撞帧 F 坐标（F=(worldX, worldY, -worldZ)）。
    // 桥前地面 y=507.45，桥面顶 y=519.58（抬升 12.13 > STEP_HEIGHT=10）。
    // 起点在桥前地面，朝 -F_x（= world 北移）接近桥；桥前缘有一小段窄引桥坡，
    // 临界步长 ≈5.504：5.33 单步落在坡面（抬升 <10）上桥，5.68 单步叉开坡面跌回桥下。
    private static final float FX = 7158.0f;
    private static final float FY = 507.45f;
    private static final float FZ = 5036.5f;
    private static final double ANGLE = -Math.PI / 2; // -F_x 方向

    @Test
    public void step533ClimbsBridge() {
        CollisionMesh.MoveResult r = mesh.checkNextMove(FX, FY, FZ, ANGLE, 1365.0 / 256.0, 11);
        assertFalse("5.33 应上桥", r.collision);
        assertTrue("5.33 应抬升上桥，实际 y=" + r.y, r.y > FY + 5);
    }

    @Test
    public void step568CcdDoesNotTunnel() {
        CollisionMesh.MoveResult r = mesh.checkNextMoveCcd(FX, FY, FZ, ANGLE, 1455.0 / 256.0, 11);
        assertTrue("5.68 不应穿透桥，实际 y=" + r.y, r.y >= FY);
    }

    @Test
    public void bigStep16CcdDoesNotTunnel() {
        CollisionMesh.MoveResult r = mesh.checkNextMoveCcd(FX, FY, FZ, ANGLE, 16.0, 11);
        assertTrue("大步 16 不应穿透桥，实际 y=" + r.y, r.y >= FY);
    }

    // 基线：证明单步 checkNextMove(5.68) 确实穿桥（y 跌到桥下），CCD 是必要的。
    @Test
    public void baselineStep568RawTunnels() {
        CollisionMesh.MoveResult r = mesh.checkNextMove(FX, FY, FZ, ANGLE, 1455.0 / 256.0, 11);
        assertTrue("5.68 单步应穿桥（基线），实际 y=" + r.y, r.y < FY);
    }
}
