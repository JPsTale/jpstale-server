package org.jpstale.server.game.tool;

import org.jpstale.assets.smd.CollisionMesh;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 无头碰撞性能基准：N 实体 × M tick 的移动+碰撞，输出每 tick 平均/最大/P95 耗时。
 * 目标负载 200 怪 + 100 玩家 = 300 实体。
 *
 * 运行：
 *   mvn -pl pt-game-server -am exec:java -Dexec.mainClass=org.jpstale.server.game.tool.CollisionBenchmark \
 *       -Dexec.args="300 1000" -Dpt.smd.root=E:\JPsTale\client
 */
public class CollisionBenchmark {

    static final class Entity {
        double x, y, z;
        double angle;
    }

    public static void main(String[] args) {
        String root = System.getProperty("pt.smd.root", "E:\\JPsTale\\client");
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 1000;

        FieldMap fm = FieldMap.FIELD_2;
        File f = new File(root, "field/" + fm.smd);
        if (!f.exists()) {
            System.err.println("smd not found: " + f);
            System.exit(1);
        }
        MapMesh mapMesh = MapMesh.load(f);
        CollisionMesh cm = mapMesh.getCollision();

        double[] verts = mapMesh.getVertices();
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            double x = verts[i], z = verts[i + 2]; // world double：(rawX/256, rawY/256, -rawZ/256)
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }

        Random rnd = new Random(42);
        List<Entity> entities = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Entity e = new Entity();
            // 采样一个可站立地面（getFloorHeight 传极大 currentY → 取最高面），避免全部落空被挡
            Double gy = null;
            int tries = 0;
            while (gy == null && tries < 100) {
                e.x = minX + rnd.nextDouble() * (maxX - minX);
                e.z = minZ + rnd.nextDouble() * (maxZ - minZ);
                gy = cm.getFloorHeight(e.x, e.z, Double.MAX_VALUE);
                tries++;
            }
            e.y = gy != null ? gy : 0;
            e.angle = rnd.nextDouble() * Math.PI * 2;
            entities.add(e);
        }

        // 走：3 world/步（单次 checkNextMove）；跑：15.5 world/步（触发 CCD 子步细分）
        benchmark(cm, entities, 3.0, ticks);
        benchmark(cm, entities, 15.5, ticks);
    }

    static void benchmark(CollisionMesh cm, List<Entity> entities, double step, int ticks) {
        // 预热
        for (int t = 0; t < 20; t++) tick(cm, entities, step);

        List<Long> perTick = new ArrayList<>(ticks);
        int blocked = 0;
        for (int t = 0; t < ticks; t++) {
            long t0 = System.nanoTime();
            blocked += tick(cm, entities, step);
            perTick.add(System.nanoTime() - t0);
        }

        Collections.sort(perTick);
        long total = 0;
        for (long v : perTick) total += v;
        double avg = total / (double) ticks;
        long p95 = perTick.get((int) (perTick.size() * 0.95));
        long max = perTick.get(perTick.size() - 1);

        System.out.printf("step=%.1f entities=%d ticks=%d | avg=%.1fµs max=%dµs p95=%dµs | blocked=%d%n",
                step, entities.size(), ticks, avg / 1000.0, max / 1000, p95 / 1000, blocked);
    }

    static int tick(CollisionMesh cm, List<Entity> entities, double step) {
        int blocked = 0;
        for (Entity e : entities) {
            CollisionMesh.MoveResult r = cm.checkNextMoveCcd(e.x, e.y, e.z, e.angle, step, 11);
            if (r.collision) blocked++;
            e.x = r.x; e.y = r.y; e.z = r.z;
            // 面朝随机游走，避免长时间原地
            e.angle += (Math.random() - 0.5) * 0.3;
        }
        return blocked;
    }
}
