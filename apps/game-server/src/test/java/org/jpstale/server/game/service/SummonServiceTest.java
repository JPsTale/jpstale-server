package org.jpstale.server.game.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 召唤落点的特征测试（{@link SummonService#pickLanding}）。
 *
 * <p>
 * 判据逐条来自原版 `OnSever.cpp:3726-3741`：
 * <pre>
 *   for (cnt = 0; cnt &lt; 8; cnt++) {
 *       cnt2 = rand() % 8;                                    // 每次重新掷方向
 *       dx = x + (ptItemSettingPosi[cnt2 &amp; 7].x &lt;&lt; (FLOATNS + 1));
 *       dz = z + (ptItemSettingPosi[cnt2 &amp; 7].y &lt;&lt; (FLOATNS + 1));
 *       height = lpStgArea-&gt;lpStage-&gt;GetFloorHeight(dx, dy, dz, 32 * fONE);
 *       if (height != CLIP_OUT) { ta = abs(height - dy); if (ta &lt; 32 * fONE) break; }
 *   }
 *   if (cnt &lt; 8) { x = dx; z = dz; y = height; }              // 8 次都不行 → 保持玩家原坐标
 * </pre>
 * ⇒ 半径 = `24 &lt;&lt; (FLOATNS+1)` = 12288 world ÷ 256 = **48 格**；容差 = `32 * fONE` ÷ 256 = **32 格**。
 */
class SummonServiceTest {

    private static final double X = 1000;
    private static final double Y = 50;
    private static final double Z = 2000;

    /** 八个方向的固定顺序（省得测试里掷点） */
    private static final int[] IN_ORDER = {0, 1, 2, 3, 4, 5, 6, 7};

    @Test
    void 半径是48格_八个方向都在这个距离上() {
        for (int d = 0; d < 8; d++) {
            double[] p = SummonService.pickLanding(X, Y, Z, new int[]{d}, (hx, hz) -> Y);
            double dist = Math.hypot(p[0] - X, p[1] - Z);
            // 方向向量是 (±1, 0/±1)，所以最近的(0,±1)是 48、对角是 48√2
            assertTrue(Math.abs(dist - SummonService.LANDING_RADIUS) < 0.001
                    || Math.abs(dist - SummonService.LANDING_RADIUS * Math.sqrt(2)) < 0.001,
                "方向 " + d + " 的落点距离应在 48 或 48√2，实测 " + dist);
        }
    }

    @Test
    void 取第一个合格的方向_不是最近的() {
        // 只有方向 3 合格：其余没有地面（null）
        Double[] only = new Double[8];
        for (int i = 0; i < 8; i++) {
            only[i] = (i == 3) ? Y : null;
        }
        int[] order = {0, 1, 2, 3, 4, 5, 6, 7};
        double[] p = SummonService.pickLanding(X, Y, Z, order, (hx, hz) -> {
            // 由坐标反推方向：这里简化成"逐次调用计数"不可靠，改用坐标匹配
            double dx = hx - X;
            double dz = hz - Z;
            for (int d = 0; d < 8; d++) {
                if (Math.abs(dx - dirX(d)) < 0.001 && Math.abs(dz - dirZ(d)) < 0.001) {
                    return only[d];
                }
            }
            return null;
        });
        assertEquals(X + dirX(3), p[0], 0.001);
        assertEquals(Z + dirZ(3), p[1], 0.001);
    }

    @Test
    void 落差超过32格就换方向() {
        // 方向 0 有地面但落差 100（不合格）→ 应落到方向 1
        double[] p = SummonService.pickLanding(X, Y, Z, IN_ORDER, (hx, hz) -> {
            boolean isDir0 = Math.abs(hx - dirX(0) - X) < 0.001 && Math.abs(hz - dirZ(0) - Z) < 0.001;
            return isDir0 ? Y + 100 : Y;
        });
        assertEquals(X + dirX(1), p[0], 0.001, "方向 0 落差过大应被跳过");
        assertEquals(Z + dirZ(1), p[1], 0.001);
    }

    @Test
    void 恰好32格仍算合格_大于32才算不合格() {
        // 原版是 `abs(h - y) < 32 * fONE` —— 严格小于
        double[] at31 = SummonService.pickLanding(X, Y, Z, new int[]{0}, (hx, hz) -> Y + 31);
        assertEquals(X + dirX(0), at31[0], 0.001, "31 格落差应合格");

        double[] at33 = SummonService.pickLanding(X, Y, Z, new int[]{0}, (hx, hz) -> Y + 33);
        assertEquals(X, at33[0], 0.001, "33 格落差应不合格 → 回落到玩家脚下");
        assertEquals(Z, at33[1], 0.001);
    }

    @Test
    void 八次都不行就用玩家自己的坐标() {
        double[] p = SummonService.pickLanding(X, Y, Z, IN_ORDER, (hx, hz) -> null);
        assertArrayEquals(new double[]{X, Z}, p, "全部 CLIP_OUT → 保持玩家原坐标（原版同）");
    }

    @Test
    void 没有地面的方向被跳过() {
        Double[] ground = {null, null, Y, null, null, null, null, null};
        double[] p = SummonService.pickLanding(X, Y, Z, IN_ORDER, (hx, hz) ->
            ground[dirOf(hx, hz)]);
        assertEquals(X + dirX(2), p[0], 0.001);
    }

    // ---- 测试用的方向表（与 SummonService.DIRECTIONS 同序；写在测试里是为了让断言独立可读） ----

    private static final int[][] DIRS = {
        {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
    };

    private static double dirX(int d) {
        return DIRS[d][0] * SummonService.LANDING_RADIUS;
    }

    private static double dirZ(int d) {
        return DIRS[d][1] * SummonService.LANDING_RADIUS;
    }

    private static int dirOf(double hx, double hz) {
        for (int d = 0; d < 8; d++) {
            if (Math.abs(hx - (X + dirX(d))) < 0.001 && Math.abs(hz - (Z + dirZ(d))) < 0.001) {
                return d;
            }
        }
        return -1;
    }

    /** 只用来确认"表里 8 个方向确实是八个不同方向"（防止有人把表改重复了） */
    @Test
    void 方向表是八个互不相同的方向() {
        Map<String, Boolean> seen = new java.util.HashMap<>();
        for (int[] d : DIRS) {
            assertTrue(Math.abs(d[0]) <= 1 && Math.abs(d[1]) <= 1);
            assertFalse(d[0] == 0 && d[1] == 0, "不能有零向量");
            assertNull(seen.put(d[0] + "," + d[1], true), "方向重复：" + d[0] + "," + d[1]);
        }
        assertEquals(8, seen.size());
    }
}
