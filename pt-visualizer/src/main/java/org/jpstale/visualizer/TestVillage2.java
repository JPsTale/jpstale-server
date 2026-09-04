package org.jpstale.visualizer;

import org.jpstale.assets.smd.SmdMapData;
import org.jpstale.assets.smd.SmdMapLoader;
import org.jpstale.server.game.model.FieldCatalog;
import org.jpstale.server.game.model.FieldInfo;
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
        FieldInfo fm = FieldCatalog.get().get(3);

        int mapId = fm.getId();
        Path file = Path.of(smdRoot, "field", fm.getModel());
        if (!Files.exists(file)) {
            System.out.println("skip (no file): map" + mapId + " " + fm.getModel());
            return;
        }
        try {
            MapMesh mesh = MapMesh.load(new File(smdRoot, "field/" + fm.getModel()));
            int[] sp = fm.getStartPoints().get(0);
            System.out.println(mesh.getHeight(sp[0], sp[1]));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
