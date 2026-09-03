package org.jpstale.server.game.collision;

import org.jpstale.server.game.model.MapMesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 碰撞网格 —— collision.ts 的世界坐标版移植。
 * <p>
 * 数值域 = 客户端 raw/256（碰撞帧 F）：与客户端 raw 是均匀缩放，布尔结果逐位等价。
 * 碰撞帧 F 恰好等于服务端世界坐标（worldX, worldY, worldZ），无需边界转换——
 * fromMapMesh 直接把 jME3 顶点换成世界坐标（见其注释的推导）。
 */
public class CollisionMesh {

    public static final float STEP_HEIGHT = 10f;
    public static final float CELL_SIZE = 64f;
    public static final float AREA_RADIUS = 64f;
    /** CCD 子步上限（临界步长，约 1409/256；大步必穿桥，子步 ≤ 此值则安全） */
    public static final double CCD_MAX_STEP = 5.504;
    private static final long CELL_KEY_MULT = 65536L;

    public static class Tri {
        public float x1, y1, z1, x2, y2, z2, x3, y3, z3;
        public float minX, maxX, minY, maxY, minZ, maxZ;
        public float nyNorm;
    }

    public static class MoveResult {
        public float x, y, z;
        public boolean collision;
    }

    private final List<Tri> triangles = new ArrayList<>();
    private final Map<Long, List<Integer>> cellMap = new HashMap<>();

    /** 测试用：直接从三角形列表构建 */
    CollisionMesh(List<Tri> tris) {
        this.triangles.addAll(tris);
        buildCellMap();
    }

    /** 从 MapMesh 构建碰撞三角形。
     * <p>
     * 碰撞帧 F = 服务端世界坐标（worldX, worldY, worldZ）。推导：
     * 客户端 smd-parser 读 raw 整数不取反 (fx@+8, fy@+12, fz@+16)；StageVertex 读成
     * jME3 = (-fz/256, fy/256, -fx/256)；MapRegionService.getHeight(-z,-x) 确立了
     * jME3.x=-worldZ、jME3.z=-worldX。故 raw/256 = (-jME3.z, jME3.y, -jME3.x) = (worldX, worldY, worldZ)。
     */
    public static CollisionMesh fromMapMesh(MapMesh mesh) {
        float[] v = mesh.getVertices();
        int[] idx = mesh.getIndices();
        int f = idx.length / 3;
        List<Tri> tris = new ArrayList<>(f);
        for (int i = 0; i < f; i++) {
            int i0 = idx[i * 3], i1 = idx[i * 3 + 1], i2 = idx[i * 3 + 2];
            Tri t = new Tri();
            t.x1 = -v[i0 * 3 + 2]; t.y1 = v[i0 * 3 + 1]; t.z1 = -v[i0 * 3];
            t.x2 = -v[i1 * 3 + 2]; t.y2 = v[i1 * 3 + 1]; t.z2 = -v[i1 * 3];
            t.x3 = -v[i2 * 3 + 2]; t.y3 = v[i2 * 3 + 1]; t.z3 = -v[i2 * 3];
            t.minX = Math.min(Math.min(t.x1, t.x2), t.x3);
            t.maxX = Math.max(Math.max(t.x1, t.x2), t.x3);
            t.minY = Math.min(Math.min(t.y1, t.y2), t.y3);
            t.maxY = Math.max(Math.max(t.y1, t.y2), t.y3);
            t.minZ = Math.min(Math.min(t.z1, t.z2), t.z3);
            t.maxZ = Math.max(Math.max(t.z1, t.z2), t.z3);
            float ux = t.x2 - t.x1, uy = t.y2 - t.y1, uz = t.z2 - t.z1;
            float vx = t.x3 - t.x1, vy = t.y3 - t.y1, vz = t.z3 - t.z1;
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            t.nyNorm = nlen > 1f / 65536f ? Math.abs(ny) / nlen : 1f;
            tris.add(t);
        }
        return new CollisionMesh(tris);
    }

    private void buildCellMap() {
        for (int i = 0; i < triangles.size(); i++) {
            Tri t = triangles.get(i);
            long cMinX = (long) Math.floor(t.minX / CELL_SIZE);
            long cMaxX = (long) Math.floor(t.maxX / CELL_SIZE);
            long cMinZ = (long) Math.floor(t.minZ / CELL_SIZE);
            long cMaxZ = (long) Math.floor(t.maxZ / CELL_SIZE);
            for (long cx = cMinX; cx <= cMaxX; cx++) {
                for (long cz = cMinZ; cz <= cMaxZ; cz++) {
                    cellMap.computeIfAbsent(cx * CELL_KEY_MULT + cz, k -> new ArrayList<>()).add(i);
                }
            }
        }
    }

    private List<Integer> nearbyTriangleIdx(float x, float z) {
        long cx0 = (long) Math.floor((x - AREA_RADIUS) / CELL_SIZE);
        long cx1 = (long) Math.floor((x + AREA_RADIUS) / CELL_SIZE);
        long cz0 = (long) Math.floor((z - AREA_RADIUS) / CELL_SIZE);
        long cz1 = (long) Math.floor((z + AREA_RADIUS) / CELL_SIZE);
        List<Integer> out = new ArrayList<>();
        for (long cx = cx0; cx <= cx1; cx++) {
            for (long cz = cz0; cz <= cz1; cz++) {
                List<Integer> arr = cellMap.get(cx * CELL_KEY_MULT + cz);
                if (arr != null) out.addAll(arr);
            }
        }
        return out;
    }

    /** getFloorHeight —— (x,z) 处可站立地面高度（上升 < STEP_HEIGHT），无则 null。 */
    public Float getFloorHeight(float x, float z, float currentY) {
        Float best = null;
        for (int i : nearbyTriangleIdx(x, z)) {
            Tri t = triangles.get(i);
            float denom = (t.z2 - t.z3) * (t.x1 - t.x3) + (t.x3 - t.x2) * (t.z1 - t.z3);
            if (Math.abs(denom) < 1f / 65536f) continue;
            float a = ((t.z2 - t.z3) * (x - t.x3) + (t.x3 - t.x2) * (z - t.z3)) / denom;
            float b = ((t.z3 - t.z1) * (x - t.x3) + (t.x1 - t.x3) * (z - t.z3)) / denom;
            float c = 1 - a - b;
            if (a >= -0.01f && b >= -0.01f && c >= -0.01f) {
                float y = a * t.y1 + b * t.y2 + c * t.y3;
                if (y - currentY < STEP_HEIGHT) {
                    if (best == null || y > best) best = y;
                }
            }
        }
        return best;
    }

    private float smPlaneProduct(float[] p1, float[] p2, float[] p3, float[] p) {
        float ux = p2[0] - p1[0], uy = p2[1] - p1[1], uz = p2[2] - p1[2];
        float vx = p3[0] - p1[0], vy = p3[1] - p1[1], vz = p3[2] - p1[2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        return nx * (p[0] - p1[0]) + ny * (p[1] - p1[1]) + nz * (p[2] - p1[2]);
    }

    private boolean triangleImact(Tri t, float[] sp, float[] ep) {
        float[] p1 = {t.x1, t.y1, t.z1};
        float[] p2 = {t.x2, t.y2, t.z2};
        float[] p3 = {t.x3, t.y3, t.z3};

        boolean spBelow = sp[1] < p1[1] && sp[1] < p2[1] && sp[1] < p3[1];
        boolean spAbove = sp[1] > p1[1] && sp[1] > p2[1] && sp[1] > p3[1];
        boolean epBelow = ep[1] < p1[1] && ep[1] < p2[1] && ep[1] < p3[1];
        boolean epAbove = ep[1] > p1[1] && ep[1] > p2[1] && ep[1] > p3[1];
        if ((spBelow || spAbove) && (epBelow || epAbove)) return false;

        float c1 = smPlaneProduct(p1, p2, p3, sp);
        float c2 = smPlaneProduct(p1, p2, p3, ep);
        if ((c1 <= 0 && c2 <= 0) || (c1 > 0 && c2 > 0)) return false;

        float vx, vy, vz;
        if (c1 <= 0) {
            vx = ep[0] - sp[0]; vy = ep[1] - sp[1]; vz = ep[2] - sp[2];
        } else {
            vx = sp[0] - ep[0]; vy = 0; vz = 0;
        }

        float[] cp1 = {p1[0] + vx, p1[1] + vy, p1[2] + vz};
        if (smPlaneProduct(p1, p2, cp1, sp) > 0) return false;
        float[] cp2 = {p2[0] + vx, p2[1] + vy, p2[2] + vz};
        if (smPlaneProduct(p2, p3, cp2, sp) > 0) return false;
        float[] cp3 = {p3[0] + vx, p3[1] + vy, p3[2] + vz};
        if (smPlaneProduct(p3, p1, cp3, sp) > 0) return false;

        return true;
    }

    private boolean wallBlocked(float x, float y, float z, float dx, float dz, int bodyWidth, int bodyHeight) {
        float bw = bodyWidth / 4.0f;
        float footY = y + 12f;
        float chestY = y + bodyHeight - bodyHeight / 4.0f;

        float dLen = (float) Math.hypot(dx, dz);
        if (dLen < 1e-6f) dLen = 1f;
        float probeLen = dLen + 12f;
        float ux = dx / dLen, uz = dz / dLen;
        float px = ux * probeLen, pz = uz * probeLen;

        float[][] lines = new float[][]{
                {x, footY, z, x + px, footY, z + pz},
                {x, chestY, z, x + px, chestY, z + pz},
                {x + px - bw, footY, z + pz, x + px + bw, footY, z + pz},
                {x + px - bw, chestY, z + pz, x + px + bw, chestY, z + pz},
        };

        float pathMinX = Math.min(x, x + px) - bw;
        float pathMaxX = Math.max(x, x + px) + bw;
        float pathMinZ = Math.min(z, z + pz) - bw;
        float pathMaxZ = Math.max(z, z + pz) + bw;

        for (int i : nearbyTriangleIdx((x + x + px) / 2, (z + z + pz) / 2)) {
            Tri t = triangles.get(i);
            if (t.maxX < pathMinX || t.minX > pathMaxX) continue;
            if (t.maxZ < pathMinZ || t.minZ > pathMaxZ) continue;
            for (float[] l : lines) {
                float lMinY = Math.min(l[1], l[4]);
                float lMaxY = Math.max(l[1], l[4]);
                if (t.maxY < lMinY || t.minY > lMaxY) continue;
                float[] sp = {l[0], l[1], l[2]};
                float[] ep = {l[3], l[4], l[5]};
                if (triangleImact(t, sp, ep)) return true;
                if (triangleImact(t, ep, sp)) return true;
            }
        }
        return false;
    }

    /** JS checkNextMove 的 curDist >>= 1（int32 截断 + 算术右移），在碰撞帧 F 里等价为 raw 整数右移再 /256。 */
    private double halveDist(double d) {
        return (((long) (d * 256.0)) >> 1) / 256.0;
    }

    /** CheckNextMove 移植（碰撞帧 F 坐标，angle 弧度，dist 世界单位）。 */
    public MoveResult checkNextMove(float x, float y, float z, double angle, double dist, int bodyWidth) {
        float prevX = x, prevY = y, prevZ = z;
        int bodyHeight = 21;
        double curDist = dist;
        double angleOffset = (768.0 / 4096.0) * Math.PI * 2.0;

        for (int ccnt = 0; ccnt < 3; ccnt++) {
            double offset = ccnt == 0 ? 0 : (ccnt == 1 ? -angleOffset : angleOffset);
            double testAngle = angle + offset;
            double fdx = Math.sin(testAngle) * curDist;
            double fdz = Math.cos(testAngle) * curDist;
            if (fdx == 0 && fdz == 0) {
                if (ccnt == 0) curDist = halveDist(curDist);
                continue;
            }
            if (wallBlocked(x, y, z, (float) fdx, (float) fdz, bodyWidth, bodyHeight)) {
                if (ccnt == 0) curDist = halveDist(curDist);
                continue;
            }
            float newX = x + (float) fdx;
            float newZ = z + (float) fdz;
            Float h = getFloorHeight(newX, newZ, y);
            if (h == null) {
                if (ccnt == 0) curDist = halveDist(curDist);
                continue;
            }
            if (h - y > STEP_HEIGHT) {
                if (ccnt == 0) curDist = halveDist(curDist);
                continue;
            }
            MoveResult r = new MoveResult();
            r.x = newX; r.y = h; r.z = newZ; r.collision = false;
            return r;
        }

        MoveResult r = new MoveResult();
        r.x = prevX; r.y = prevY; r.z = prevZ; r.collision = true;
        return r;
    }

    /** CCD 子步：大步拆成 ≤CCD_MAX_STEP 的小步依次走，防穿桥；被挡则停在最远有效位置。 */
    public MoveResult checkNextMoveCcd(float x, float y, float z, double angle, double dist, int bodyWidth) {
        float cx = x, cy = y, cz = z;
        double remaining = dist;
        while (remaining > CCD_MAX_STEP) {
            MoveResult r = checkNextMove(cx, cy, cz, angle, CCD_MAX_STEP, bodyWidth);
            if (r.collision) {
                MoveResult out = new MoveResult();
                out.x = cx; out.y = cy; out.z = cz; out.collision = true;
                return out;
            }
            cx = r.x; cy = r.y; cz = r.z;
            remaining -= CCD_MAX_STEP;
        }
        MoveResult r = checkNextMove(cx, cy, cz, angle, remaining, bodyWidth);
        if (r.collision) {
            r.x = cx; r.y = cy; r.z = cz;
        }
        return r;
    }
}
