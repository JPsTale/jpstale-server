package org.jpstale.server.game.tool;

import org.jpstale.assets.smd.CollisionMesh;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 碰撞阶段剖析：跑 N tick 后输出各阶段(checkNextMove/wallBlocked/floorHeight/CCD/nearby)耗时占比。
 *
 * 运行：
 *   mvn -o -pl pt-game-server exec:java \
 *       -Dexec.mainClass=org.jpstale.server.game.tool.CollisionProfiler \
 *       -Dexec.args="39 200 200 run" -Dpt.smd.root=E:\JPsTale\client
 *   args: mapId 默认39(fall_game)  perMap=200  ticks=200  模式=run|walk
 */
public class CollisionProfiler {

    public static void main(String[] args) {
        String root = System.getProperty("pt.smd.root", "E:\\JPsTale\\client");
        int mapId = args.length > 0 ? Integer.parseInt(args[0]) : 39;
        int perMap = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int ticks = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        boolean run = args.length <= 3 || args[3].equals("run");
        double step = run ? CollisionStressTest.RUN_STEP : CollisionStressTest.WALK_STEP;

        FieldMap fm = FieldMap.values()[mapId];
        MapMesh mm = MapMesh.load(new File(root, "field/" + fm.smd));
        if (mm == null) { System.out.println("no mesh"); return; }
        List<MapMesh> single = new ArrayList<>();
        single.add(mm);
        List<CollisionStressTest.Entity> ents = CollisionStressTest.spawn(single, perMap);
        System.out.println("map=" + fm.smd + " ents=" + ents.size()
            + " step=" + step + " ticks=" + ticks + " 模式=" + (run ? "run(CCD)" : "walk"));

        CollisionMesh.PROFILE = true;
        // 预热（不计时）
        for (int t = 0; t < 30; t++) CollisionStressTest.tick(ents, step);
        CollisionMesh.profileReset();
        long wall0 = System.nanoTime();
        for (int t = 0; t < ticks; t++) CollisionStressTest.tick(ents, step);
        long wall = System.nanoTime() - wall0;
        CollisionMesh.PROFILE = false;

        long tc = CollisionMesh.tCheckNextMove, nc = CollisionMesh.nCheckNextMove;
        long tw = CollisionMesh.tWallBlocked, nw = CollisionMesh.nWallBlocked;
        long tf = CollisionMesh.tFloorHeight, nf = CollisionMesh.nFloorHeight;
        long tccd = CollisionMesh.tCcd, nccd = CollisionMesh.nCcd;
        long tn = CollisionMesh.tNearby, nn = CollisionMesh.nNearby, ntri = CollisionMesh.nNearbyTris;

        System.out.printf("墙时钟总耗时=%.2f ms%n", wall / 1e6);
        System.out.println("---- 各阶段总耗时(ns) 与占比 ----");
        double[] a = {tw, tf, tn, tc, tccd};
        String[] name = {"wallBlocked", "getFloorHeight", "nearbyTriIdx", "checkNextMove(含子)", "checkNextMoveCcd(含子)"};
        for (int i = 0; i < a.length; i++) {
            System.out.printf("%-20s %10.2f ms  %6.1f%%%n", name[i], a[i] / 1e6, a[i] / (double) wall * 100);
        }
        System.out.println("---- 调用次数 ----");
        System.out.printf("checkNextMove=%d  (Ccd=%d, 内调 checkNextMove=%d)%n", nc, nccd, nc);
        System.out.printf("wallBlocked=%d  getFloorHeight=%d%n", nw, nf);
        System.out.printf("nearby 查询=%d 次, 共收集三角形=%d, 平均每次=%.1f 个%n", nn, ntri, nn > 0 ? ntri / (double) nn : 0);
        System.out.printf("每实体每tick: checkNextMove=%.1f 次, wallBlocked=%.1f, floorHeight=%.1f%n",
            nc / (double) (ents.size() * ticks), nw / (double) (ents.size() * ticks), nf / (double) (ents.size() * ticks));
    }
}
