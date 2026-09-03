package org.jpstale.server.game.tool;

import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 每图独立压力测试：对每张图分别 spawn M 怪跑 T tick，输出各图耗时。
 * 定位"病态图"——若某张图显著慢于其它，说明单图几何/分布拖累整体。
 *
 * 运行：
 *   mvn -o -pl pt-game-server exec:java \
 *       -Dexec.mainClass=org.jpstale.server.game.tool.CollisionPerMapStress \
 *       -Dexec.args="200 100" -Dpt.smd.root=E:\JPsTale\client
 */
public class CollisionPerMapStress {

    public static void main(String[] args) {
        String root = System.getProperty("pt.smd.root", "E:\\JPsTale\\client");
        int perMap = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        System.out.printf("%-4s %-30s %-8s %-6s %-10s %-10s %-10s%n",
            "map", "smd", "faces", "模式", "avg(ms)", "p95(ms)", "max(ms)");
        System.out.println("----");

        for (FieldMap fm : FieldMap.values()) {
            int mapId = fm.ordinal();
            File f = new File(root, "field/" + fm.smd);
            if (!f.exists()) {
                System.out.printf("%-4d %-30s skip(no file)%n", mapId, fm.smd);
                continue;
            }
            MapMesh mm = MapMesh.load(f);
            if (mm == null) {
                System.out.printf("%-4d %-30s skip(no mesh)%n", mapId, fm.smd);
                continue;
            }
            List<MapMesh> single = new ArrayList<>();
            single.add(mm);
            List<CollisionStressTest.Entity> ents = CollisionStressTest.spawn(single, perMap);
            int faces = mm.getFaceCount();

            for (boolean run : new boolean[]{false, true}) {
                double step = run ? CollisionStressTest.RUN_STEP : CollisionStressTest.WALK_STEP;
                double[] s = CollisionStressTest.benchmark(ents, step, ticks, false);
                System.out.printf("%-4d %-30s %-8d %-6s %-10.3f %-10.3f %-10.3f%n",
                    mapId, fm.smd, faces, run ? "run" : "walk", s[0], s[1], s[2]);
            }
        }
    }
}
