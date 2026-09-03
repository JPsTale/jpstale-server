package org.jpstale.server.game.collision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CollisionMeshTest {

    private static CollisionMesh.Tri tri(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
        CollisionMesh.Tri t = new CollisionMesh.Tri();
        t.x1 = x1; t.y1 = y1; t.z1 = z1; t.x2 = x2; t.y2 = y2; t.z2 = z2; t.x3 = x3; t.y3 = y3; t.z3 = z3;
        t.minX = Math.min(Math.min(x1, x2), x3); t.maxX = Math.max(Math.max(x1, x2), x3);
        t.minY = Math.min(Math.min(y1, y2), y3); t.maxY = Math.max(Math.max(y1, y2), y3);
        t.minZ = Math.min(Math.min(z1, z2), z3); t.maxZ = Math.max(Math.max(z1, z2), z3);
        t.nyNorm = 1f;
        return t;
    }

    /** 平地：走 8 单位，y 从 0 吸附到地面 3，z 前进 8。 */
    @Test
    public void flatFloorSnapsY() {
        List<CollisionMesh.Tri> tris = new ArrayList<>();
        tris.add(tri(-100, 3, -100, 100, 3, -100, 0, 3, 100));
        tris.add(tri(100, 3, -100, 0, 3, 100, 100, 3, 100));
        CollisionMesh cm = new CollisionMesh(tris);

        CollisionMesh.MoveResult r = cm.checkNextMove(0, 0, 0, 0, 8, 11);
        assertFalse(r.collision);
        assertEquals(8f, r.z, 0.01f);
        assertEquals(3f, r.y, 0.01f);
    }

    /** 台阶：rise=5 < 10 可上。 */
    @Test
    public void lowStepClimbable() {
        List<CollisionMesh.Tri> tris = new ArrayList<>();
        tris.add(tri(-100, 0, -100, 100, 0, -100, 0, 0, 10));
        tris.add(tri(100, 0, -100, 0, 0, 10, 100, 0, 10));
        tris.add(tri(-100, 5, 10, 100, 5, 10, 0, 5, 100));
        tris.add(tri(100, 5, 10, 0, 5, 100, 100, 5, 100));
        CollisionMesh cm = new CollisionMesh(tris);

        CollisionMesh.MoveResult r = cm.checkNextMove(0, 0, 0, 0, 20, 11);
        assertFalse(r.collision);
        assertEquals(5f, r.y, 0.5f);
    }

    /** 台阶过高：rise=15 > 10 被挡。高台横向拉满（x∈[-200,200]），±67.5° 侧滑也落在高台上，无低地可绕。 */
    @Test
    public void highStepBlocked() {
        List<CollisionMesh.Tri> tris = new ArrayList<>();
        tris.add(tri(-200, 0, -100, 200, 0, -100, -200, 0, 0));
        tris.add(tri(200, 0, -100, 200, 0, 0, -200, 0, 0));
        tris.add(tri(-200, 15, 0, 200, 15, 0, -200, 15, 100));
        tris.add(tri(200, 15, 0, 200, 15, 100, -200, 15, 100));
        CollisionMesh cm = new CollisionMesh(tris);

        CollisionMesh.MoveResult r = cm.checkNextMove(0, 0, 0, 0, 20, 11);
        assertTrue(r.collision);
        assertEquals(0f, r.z, 0.01f);
    }

    /** 墙：垂直墙在 z=3（近，T 线探测 12 单位内必命中）且横向拉满，±67.5° 侧滑也无法绕过。 */
    @Test
    public void verticalWallBlocks() {
        List<CollisionMesh.Tri> tris = new ArrayList<>();
        tris.add(tri(-200, 0, -100, 200, 0, -100, -200, 0, 100));
        tris.add(tri(200, 0, -100, 200, 0, 100, -200, 0, 100));
        tris.add(tri(-200, 0, 3, 200, 0, 3, -200, 20, 3));
        tris.add(tri(200, 0, 3, 200, 20, 3, -200, 20, 3));
        CollisionMesh cm = new CollisionMesh(tris);

        CollisionMesh.MoveResult r = cm.checkNextMove(0, 0, 0, 0, 20, 11);
        assertTrue(r.collision);
        assertTrue("不应穿过墙 z=3", r.z < 3f);
    }

    /** CCD：大步 16 被近墙挡，不穿墙。 */
    @Test
    public void ccdStopsAtWall() {
        List<CollisionMesh.Tri> tris = new ArrayList<>();
        tris.add(tri(-200, 0, -100, 200, 0, -100, -200, 0, 100));
        tris.add(tri(200, 0, -100, 200, 0, 100, -200, 0, 100));
        tris.add(tri(-200, 0, 3, 200, 0, 3, -200, 20, 3));
        tris.add(tri(200, 0, 3, 200, 20, 3, -200, 20, 3));
        CollisionMesh cm = new CollisionMesh(tris);

        CollisionMesh.MoveResult r = cm.checkNextMoveCcd(0, 0, 0, 0, 16, 11);
        assertTrue(r.collision);
        assertTrue("CCD 不应穿透墙", r.z < 3f);
    }
}
