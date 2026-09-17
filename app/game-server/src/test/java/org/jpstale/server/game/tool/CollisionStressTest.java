package org.jpstale.server.game.tool;

import org.jpstale.assets.smd.CollisionMesh;
import org.jpstale.server.game.model.FieldCatalog;
import org.jpstale.server.game.model.FieldInfo;
import org.jpstale.server.game.model.MapMesh;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 多图碰撞压力测试：N 张图 × 每图 M 怪 × T tick，单线程移动+碰撞。
 * 回答："每图 200 怪、单线程能否 20FPS(50ms/tick)"。
 *
 * 两种调度对比：
 * - 不分片：每 tick 全部怪移动+碰撞（最坏连续负载）
 * - i%5 分片：每 tick 只处理 id%5==tick%5 的 1/5 怪，5 tick 轮完（对齐原版错峰，20fps 下每轮等效原版 4/16fps）
 *
 * 建模（与 MovementService.updateMonster 同语义）：
 * - walk 步长 3.2 world/tick（&lt; CCD_MAX_STEP 5.504，不触发子步）
 * - run  步长 6.4 world/tick（&gt; 5.504，触发 CCD 子步，最贵路径）
 *
 * 运行：
 *   mvn -pl pt-game-server -am exec:java \
 *       -Dexec.mainClass=org.jpstale.server.game.tool.CollisionStressTest \
 *       -Dexec.args="200 200" -Dpt.smd.root=E:\JPsTale\client
 */
public class CollisionStressTest {

    static final int[] SCALES = {1, 2, 5, 10, 22, 44};
    static final double WALK_STEP = 3.2;
    static final double RUN_STEP = 6.4;
    static final int STAGGER = 5; // 分片数：i%5，5 tick 轮完一轮

    static final class Entity {
        double x, y, z, angle;
        CollisionMesh cm;
    }

    public static void main(String[] args) {
        String root = System.getProperty("pt.smd.root", "E:\\JPsTale\\client");
        int perMap = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 200;

        List<MapMesh> maps = new ArrayList<>();
        for (FieldInfo fm : FieldCatalog.get().list()) {
            File f = new File(root, "field/" + fm.getModel());
            if (!f.exists()) continue;
            MapMesh mm = MapMesh.load(f);
            if (mm != null) maps.add(mm);
        }
        System.out.println("预加载碰撞图: " + maps.size() + "/" + FieldCatalog.get().list().size());
        System.out.println("\n== 每图 " + perMap + " 怪 ==");
        System.out.println("== walk=" + WALK_STEP + " (无CCD) / run=" + RUN_STEP + " (CCD) ==");
        System.out.println("== 20FPS => 每 tick 预算 50ms；i%5 分片 => 每 tick 处理 1/5 怪 ==");
        System.out.printf("%-6s %-9s %-10s %-6s %-10s %-10s %-10s %-9s%n",
            "图数", "实体", "调度", "模式", "avg(ms)", "p95(ms)", "max(ms)", "max/50ms");

        for (int scale : SCALES) {
            List<MapMesh> used = maps.subList(0, Math.min(scale, maps.size()));
            List<Entity> ents = spawn(used, perMap);
            for (boolean run : new boolean[]{false, true}) {
                double step = run ? RUN_STEP : WALK_STEP;
                double[] sAll = benchmark(ents, step, ticks, false);
                System.out.printf("%-6d %-9d %-10s %-6s %-10.3f %-10.3f %-10.3f %-8.0f%%%n",
                    used.size(), ents.size(), "不分片", run ? "run" : "walk", sAll[0], sAll[1], sAll[2],
                    (sAll[2] / 50.0) * 100.0);
                double[] sStag = benchmark(ents, step, ticks, true);
                System.out.printf("%-6d %-9d %-10s %-6s %-10.3f %-10.3f %-10.3f %-8.0f%%%n",
                    used.size(), ents.size(), "i%5分片", run ? "run" : "walk", sStag[0], sStag[1], sStag[2],
                    (sStag[2] / 50.0) * 100.0);
            }
        }
    }

    /** 每张 used 图 spawn perMap 个实体：在各自图 AABB 内随机采样可站地面。 */
    static List<Entity> spawn(List<MapMesh> maps, int perMap) {
        Random rnd = new Random(42);
        List<Entity> out = new ArrayList<>();
        for (MapMesh mm : maps) {
            CollisionMesh cm = mm.getCollision();
            double mnX = mm.getMinX(), mxX = mm.getMaxX();
            double mnZ = mm.getMinZ(), mxZ = mm.getMaxZ();
            for (int i = 0; i < perMap; i++) {
                Entity e = null;
                for (int t = 0; t < 60; t++) {
                    Entity cand = new Entity();
                    cand.cm = cm;
                    cand.x = mnX + rnd.nextDouble() * (mxX - mnX);
                    cand.z = mnZ + rnd.nextDouble() * (mxZ - mnZ);
                    Double gy = cm.getFloorHeight(cand.x, cand.z, Double.MAX_VALUE);
                    if (gy == null) continue;
                    cand.y = gy;
                    cand.angle = rnd.nextDouble() * Math.PI * 2;
                    e = cand;
                    break;
                }
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    /**
     * 跑 ticks 次移动；stagger=true 时每 tick 只处理 id%5==frameIdx%5 的 1/5 怪。
     * 返回 [avg, p95, max] 毫秒。
     */
    static double[] benchmark(List<Entity> ents, double step, int ticks, boolean stagger) {
        for (int t = 0; t < 25; t++) tick(ents, step, stagger, t); // 预热（轮转）
        List<Long> times = new ArrayList<>(ticks);
        for (int t = 0; t < ticks; t++) {
            long t0 = System.nanoTime();
            tick(ents, step, stagger, t);
            times.add(System.nanoTime() - t0);
        }
        Collections.sort(times);
        long sum = 0;
        for (long v : times) sum += v;
        double avg = sum / (double) ticks / 1e6;
        double p95 = times.get((int) (times.size() * 0.95)) / 1e6;
        double max = times.get(times.size() - 1) / 1e6;
        return new double[]{avg, p95, max};
    }

    /** 走一步；stagger 时仅移动 id%STAGGER==frameIdx%STAGGER 的怪。 */
    public static void tick(List<Entity> ents, double step) {
        tick(ents, step, false, 0);
    }

    public static void tick(List<Entity> ents, double step, boolean stagger, int frameIdx) {
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        int frameMod = stagger ? frameIdx % STAGGER : -1;
        for (int idx = 0; idx < ents.size(); idx++) {
            Entity e = ents.get(idx);
            if (stagger && (idx % STAGGER) != frameMod) continue;
            CollisionMesh.MoveResult r = e.cm.checkNextMoveCcd(e.x, e.y, e.z, e.angle, step, 11);
            if (!r.collision) {
                e.x = r.x; e.y = r.y; e.z = r.z;
            }
            // 随机转向，避免原地打转；小幅扰动近似自由移动
            e.angle += (rnd.nextDouble() - 0.5) * 0.4;
        }
    }
}
