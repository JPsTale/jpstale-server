package org.jpstale.assets.smd;

import org.jpstale.assets.smd.SmdMapData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 碰撞网格 —— world double 域（服务端统一运算坐标）。
 * <p>
 * 坐标域：(x, y, z) = (rawX/256, rawY/256, -rawZ/256)，北正，double 存储运算保持精度。
 * 与 jpstale-web 渲染域（selfPos）同向 —— 客户端看到的坐标与服务端运算的坐标完全一致。
 * raw int 只在 {@link SmdMapData} 读取层存在，进碰撞即转 world。
 * <p>
 * 逻辑复刻自 collision.ts（smStage3d.cpp CheckNextMove / GetPolyHeight / smMakeTLine）：
 * - 只认 meshState &amp; 1 实体面为碰撞面
 * - getFloorHeight：重心采样，只认抬升 &lt; STEP_HEIGHT 的可站立面
 * - 4 条 T 形探测线（脚+胸，前+两侧）射线-三角形相交，含法线背面双面判定
 * - 三角度试探（主方向 ±768/4096 圈），主方向失败距离减半
 * - CCD：大步拆 ≤ CCD_MAX_STEP 子步，防穿桥
 */
public class CollisionMesh {

    /** 台阶高度阈值（world 单位） */
    public static final double STEP_HEIGHT = 10.0;
    /** cell 边长（world 单位，对应 raw 64） */
    public static final double CELL_SIZE = 64.0;
    /** 邻近查询半径（world 单位） */
    public static final double AREA_RADIUS = 64.0;
    /** CCD 子步上限（world 单位；临界步长实测 ≈5.504，大步必穿桥） */
    public static final double CCD_MAX_STEP = 5.504;
    private static final long CELL_KEY_MULT = 65536L;

    public static class Tri {
        public double x1, y1, z1, x2, y2, z2, x3, y3, z3;
        public double minX, maxX, minY, maxY, minZ, maxZ;
        public double nyNorm;
    }

    public static class MoveResult {
        public double x, y, z;
        public boolean collision;
    }

    private final List<Tri> triangles = new ArrayList<>();
    private final Map<Long, List<Integer>> cellMap = new HashMap<>();

    /** 测试/工具用：直接从三角形列表构建 */
    public CollisionMesh(List<Tri> tris) {
        this.triangles.addAll(tris);
        buildCellMap();
    }

    /**
     * 从 {@link SmdMapData} 构建（world double）：取 meshState &amp; 1 碰撞面，
     * 顶点经 {@code vertsWorldDouble()} 转 (rawX/256, rawY/256, -rawZ/256)。
     */
    public static CollisionMesh fromSmd(SmdMapData d) {
        double[] v = d.vertsWorldDouble();
        int[] idx = d.solidFaceIndices();
        int f = idx.length / 3;
        List<Tri> tris = new ArrayList<>(f);
        for (int i = 0; i < f; i++) {
            int i0 = idx[i * 3], i1 = idx[i * 3 + 1], i2 = idx[i * 3 + 2];
            tris.add(makeTri(
                    v[i0 * 3], v[i0 * 3 + 1], v[i0 * 3 + 2],
                    v[i1 * 3], v[i1 * 3 + 1], v[i1 * 3 + 2],
                    v[i2 * 3], v[i2 * 3 + 1], v[i2 * 3 + 2]));
        }
        return new CollisionMesh(tris);
    }

    static Tri makeTri(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3) {
        Tri t = new Tri();
        t.x1 = x1; t.y1 = y1; t.z1 = z1;
        t.x2 = x2; t.y2 = y2; t.z2 = z2;
        t.x3 = x3; t.y3 = y3; t.z3 = z3;
        t.minX = Math.min(Math.min(x1, x2), x3);
        t.maxX = Math.max(Math.max(x1, x2), x3);
        t.minY = Math.min(Math.min(y1, y2), y3);
        t.maxY = Math.max(Math.max(y1, y2), y3);
        t.minZ = Math.min(Math.min(z1, z2), z3);
        t.maxZ = Math.max(Math.max(z1, z2), z3);
        double ux = x2 - x1, uy = y2 - y1, uz = z2 - z1;
        double vx = x3 - x1, vy = y3 - y1, vz = z3 - z1;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double nlen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        t.nyNorm = nlen > 1e-6 ? Math.abs(ny) / nlen : 1.0;
        return t;
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

    private List<Integer> nearbyTriangleIdx(double x, double z) {
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

    /**
     * (x,z) 邻近的碰撞三角形 —— checkNextMove/wallBlocked/getFloorHeight 用同一候选集合，
     * 即"该点实际参与检测"的三角形。供可视化调试（把当前参与的面单独画出来）。
     * 返回的 Tri 引用三角形内部对象，调用方只读。
     */
    public List<Tri> nearbyTriangles(double x, double z) {
        List<Integer> idxs = nearbyTriangleIdx(x, z);
        List<Tri> out = new ArrayList<>(idxs.size());
        for (int i : idxs) out.add(triangles.get(i));
        return out;
    }

    /** (x,z) 处可站立地面高度（上升 < STEP_HEIGHT），无则 null。 */
    public Double getFloorHeight(double x, double z, double currentY) {
        Double best = null;
        for (int i : nearbyTriangleIdx(x, z)) {
            Tri t = triangles.get(i);
            double denom = (t.z2 - t.z3) * (t.x1 - t.x3) + (t.x3 - t.x2) * (t.z1 - t.z3);
            if (Math.abs(denom) < 1e-9) continue;
            double a = ((t.z2 - t.z3) * (x - t.x3) + (t.x3 - t.x2) * (z - t.z3)) / denom;
            double b = ((t.z3 - t.z1) * (x - t.x3) + (t.x1 - t.x3) * (z - t.z3)) / denom;
            double c = 1 - a - b;
            if (a >= -0.01 && b >= -0.01 && c >= -0.01) {
                double y = a * t.y1 + b * t.y2 + c * t.y3;
                if (y - currentY < STEP_HEIGHT) {
                    if (best == null || y > best) best = y;
                }
            }
        }
        return best;
    }

    private double smPlaneProduct(double[] p1, double[] p2, double[] p3, double[] p) {
        double ux = p2[0] - p1[0], uy = p2[1] - p1[1], uz = p2[2] - p1[2];
        double vx = p3[0] - p1[0], vy = p3[1] - p1[1], vz = p3[2] - p1[2];
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        return nx * (p[0] - p1[0]) + ny * (p[1] - p1[1]) + nz * (p[2] - p1[2]);
    }

    private boolean triangleImact(Tri t, double[] sp, double[] ep) {
        double[] p1 = {t.x1, t.y1, t.z1};
        double[] p2 = {t.x2, t.y2, t.z2};
        double[] p3 = {t.x3, t.y3, t.z3};

        boolean spBelow = sp[1] < p1[1] && sp[1] < p2[1] && sp[1] < p3[1];
        boolean spAbove = sp[1] > p1[1] && sp[1] > p2[1] && sp[1] > p3[1];
        boolean epBelow = ep[1] < p1[1] && ep[1] < p2[1] && ep[1] < p3[1];
        boolean epAbove = ep[1] > p1[1] && ep[1] > p2[1] && ep[1] > p3[1];
        if ((spBelow || spAbove) && (epBelow || epAbove)) return false;

        double c1 = smPlaneProduct(p1, p2, p3, sp);
        double c2 = smPlaneProduct(p1, p2, p3, ep);
        if ((c1 <= 0 && c2 <= 0) || (c1 > 0 && c2 > 0)) return false;

        double vx, vy, vz;
        if (c1 <= 0) {
            vx = ep[0] - sp[0]; vy = ep[1] - sp[1]; vz = ep[2] - sp[2];
        } else {
            vx = sp[0] - ep[0]; vy = 0; vz = 0;
        }

        double[] cp1 = {p1[0] + vx, p1[1] + vy, p1[2] + vz};
        if (smPlaneProduct(p1, p2, cp1, sp) > 0) return false;
        double[] cp2 = {p2[0] + vx, p2[1] + vy, p2[2] + vz};
        if (smPlaneProduct(p2, p3, cp2, sp) > 0) return false;
        double[] cp3 = {p3[0] + vx, p3[1] + vy, p3[2] + vz};
        if (smPlaneProduct(p3, p1, cp3, sp) > 0) return false;

        return true;
    }

    private boolean wallBlocked(double x, double y, double z, double dx, double dz, int bodyWidth, int bodyHeight) {
        double bw = bodyWidth / 4.0;
        double footY = y + 12.0;
        double chestY = y + bodyHeight - bodyHeight / 4.0;

        double dLen = Math.hypot(dx, dz);
        if (dLen < 1e-6) dLen = 1.0;
        double probeLen = dLen + 12.0;
        double ux = dx / dLen, uz = dz / dLen;
        double px = ux * probeLen, pz = uz * probeLen;

        double[][] lines = new double[][]{
                {x, footY, z, x + px, footY, z + pz},
                {x, chestY, z, x + px, chestY, z + pz},
                {x + px - bw, footY, z + pz, x + px + bw, footY, z + pz},
                {x + px - bw, chestY, z + pz, x + px + bw, chestY, z + pz},
        };

        double pathMinX = Math.min(x, x + px) - bw;
        double pathMaxX = Math.max(x, x + px) + bw;
        double pathMinZ = Math.min(z, z + pz) - bw;
        double pathMaxZ = Math.max(z, z + pz) + bw;

        for (int i : nearbyTriangleIdx((x + x + px) / 2, (z + z + pz) / 2)) {
            Tri t = triangles.get(i);
            if (t.maxX < pathMinX || t.minX > pathMaxX) continue;
            if (t.maxZ < pathMinZ || t.minZ > pathMaxZ) continue;
            for (double[] l : lines) {
                double lMinY = Math.min(l[1], l[4]);
                double lMaxY = Math.max(l[1], l[4]);
                if (t.maxY < lMinY || t.minY > lMaxY) continue;
                double[] sp = {l[0], l[1], l[2]};
                double[] ep = {l[3], l[4], l[5]};
                if (triangleImact(t, sp, ep)) return true;
                if (triangleImact(t, ep, sp)) return true;
            }
        }
        return false;
    }

    /** CheckNextMove（world double，angle 弧度，dist world 单位）。 */
    public MoveResult checkNextMove(double x, double y, double z, double angle, double dist, int bodyWidth) {
        double prevX = x, prevY = y, prevZ = z;
        int bodyHeight = 21;
        double curDist = dist;
        double angleOffset = (768.0 / 4096.0) * Math.PI * 2.0;

        for (int ccnt = 0; ccnt < 3; ccnt++) {
            double offset = ccnt == 0 ? 0 : (ccnt == 1 ? -angleOffset : angleOffset);
            double testAngle = angle + offset;
            double fdx = Math.sin(testAngle) * curDist;
            double fdz = Math.cos(testAngle) * curDist;
            if (fdx == 0 && fdz == 0) {
                if (ccnt == 0) curDist /= 2.0;
                continue;
            }
            if (wallBlocked(x, y, z, fdx, fdz, bodyWidth, bodyHeight)) {
                if (ccnt == 0) curDist /= 2.0;
                continue;
            }
            double newX = x + fdx;
            double newZ = z + fdz;
            Double h = getFloorHeight(newX, newZ, y);
            if (h == null) {
                if (ccnt == 0) curDist /= 2.0;
                continue;
            }
            if (h - y > STEP_HEIGHT) {
                if (ccnt == 0) curDist /= 2.0;
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

    /** CCD 子步：大步拆成 ≤CCD_MAX_STEP 的子步，防穿桥；被挡则停在最远有效位置。 */
    public MoveResult checkNextMoveCcd(double x, double y, double z, double angle, double dist, int bodyWidth) {
        double cx = x, cy = y, cz = z;
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
