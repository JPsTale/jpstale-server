package org.jpstale.visualizer;

import org.jpstale.assets.smd.SmdMapData;
import org.jpstale.assets.smd.SmdMapLoader;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * desc:
 *
 * @author yanmaoyuan
 */
public class TestVillage2 {
    public static void main(String[] args) {
        String smdRoot = args.length > 0 ? args[0] : "E:/JPsTale/client";
        FieldMap fm = FieldMap.FIELD_3;

        int mapId = fm.ordinal();
        Path file = Path.of(smdRoot, "field", fm.smd);
        if (!Files.exists(file)) {
            System.out.println("skip (no file): map" + mapId + " " + fm.smd);
            return;
        }
        try {
            MapMesh mesh = MapMesh.load(new File(smdRoot, "field/" + fm.smd));
            System.out.println(mesh.getHeight(fm.startPoints[0][0], fm.startPoints[0][1]));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
