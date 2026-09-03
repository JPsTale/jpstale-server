package org.jpstale.visualizer;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferUtils;
import org.jpstale.assets.smd.SmdMapData;
import org.jpstale.assets.smd.SmdMapLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * jME3 wireframe 可视化：读取全部 44 张 FieldMap 的 .smd 碰撞网格 wireframe 渲染于同一场景，
 * 并把 FieldMap 的 center / startPoints / gates / warpGates 坐标用彩色标记点显示。
 *
 * 运行：
 *   mvn -f pt-visualizer/pom.xml compile exec:java \
 *       -Dexec.mainClass=org.jpstale.visualizer.MapWireframeApp -Dexec.args="E:/JPsTale/client"
 *
 * 坐标域：world (x/256, y/256, -z/256) 北正 —— FieldMap 坐标与 smd 顶点同域，
 * 标记点以同坐标放入 maps Node 下即可与 wireframe 对齐。
 */
public class MapWireframeApp extends SimpleApplication {

    private final String smdRoot;

    /** mapId -> 该图 SmdMapData（spawn box 贴地面用）。加载后填充。 */
    private final SmdMapData[] mapData = new SmdMapData[FieldMap.values().length];

    public MapWireframeApp(String smdRoot) {
        this.smdRoot = smdRoot;
    }

    @Override
    public void simpleInitApp() {
        flyCam.setMoveSpeed(2000f);
        cam.setFrustumFar(4000f);

        Node maps = new Node("maps");
        maps.setLocalScale(0.1f);
        rootNode.attachChild(maps);

        FieldMap[] fields = FieldMap.values();
        int loaded = 0;
        for (FieldMap fm : fields) {
            int mapId = fm.ordinal();
            Path file = Path.of(smdRoot, "field", fm.smd);
            if (!Files.exists(file)) {
                System.out.println("skip (no file): map" + mapId + " " + fm.smd);
                continue;
            }
            try {
                SmdMapData data = SmdMapLoader.load(file);
                if (data.nFace <= 0) {
                    System.out.println("skip (empty): map" + mapId + " " + fm.smd);
                    continue;
                }
                mapData[mapId] = data;
                Geometry g = buildCollisionGeometry(data);
                g.setName("map" + mapId);
                maps.attachChild(g);
                loaded++;

                Node markers = buildFieldMapMarkers(fm, data);
                maps.attachChild(markers);

                System.out.println("map" + mapId + " " + fm.smd
                    + " verts=" + data.nVertex + " solidFaces=" + solidFaceCount(data)
                    + " markers=" + markers.getQuantity());
            } catch (Exception e) {
                System.out.println("error map" + mapId + " " + fm.smd + ": " + e);
            }
        }
        System.out.println("Loaded " + loaded + "/" + fields.length + " maps");

        // DB mapspawnpoint → 红色小 box
        loadDbSpawns(maps);

        System.out.println("markers: center=黄  startPoint=绿  gate=红  warpGate=品红  spawnBox=红  (0.1 scale, FlyCam 飞行)");
    }

    static int solidFaceCount(SmdMapData d) {
        int c = 0;
        for (int fi = 0; fi < d.nFace; fi++) if (d.isSolidFace(fi)) c++;
        return c;
    }

    /** DB 连接参数（与 pt-game-server 一致，仅 host 换本机可访问的 192.168.31.10）。 */
    static final String DB_URL = "jdbc:postgresql://192.168.31.10:5432/pristontale";
    static final String DB_USER = "sa";
    static final String DB_PASSWORD = "632514Go";

    /** 从 gamedb.mapspawnpoint 加载全部刷怪点，红色小 box 显示（坐标为北正 world 域，与地图同域）。 */
    private void loadDbSpawns(Node maps) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Red);
        // box 半边长（world 单位；0.1 scale 下视觉 = world*0.1，小 box 用 30）
        float half = 30f;
        int count = 0;
        int skipped = 0;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT stage, x, z FROM gamedb.mapspawnpoint");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int stage = rs.getInt("stage");
                int x = rs.getInt("x");
                int z = rs.getInt("z");
                if (stage < 0 || stage >= mapData.length || mapData[stage] == null) {
                    skipped++;
                    continue;
                }
                float y = groundHeight(mapData[stage], x, z);
                Box b = new Box(half, half, half);
                Geometry g = new Geometry("spawn", b);
                g.setMaterial(mat);
                g.setLocalTranslation(x, y, z);
                g.setQueueBucket(RenderQueue.Bucket.Transparent);
                maps.attachChild(g);
                count++;
            }
        } catch (Exception e) {
            System.out.println("DB spawn load failed: " + e);
            return;
        }
        System.out.println("DB spawns loaded: " + count + " (skipped no-map=" + skipped + ")");
    }

    /**
     * 构建碰撞面 mesh（参照 jpstale SceneBuilder.buildCollisionMesh）：
     * 只取 meshState &amp; 1 的面，顶点压缩重映射到仅被碰撞面引用的顶点。
     */
    private Geometry buildCollisionGeometry(SmdMapData d) {
        double[] worldVerts = d.vertsWorldDouble(); // world：(rawX/256, rawY/256, -rawZ/256)
        int[] idx = d.solidFaceIndices();

        int[] loc = new int[d.nVertex];
        java.util.Arrays.fill(loc, -1);
        int vSize = 0;
        for (int i = 0; i < idx.length; i++) {
            int v = idx[i];
            if (loc[v] == -1) loc[v] = vSize++;
        }
        float[] pos = new float[vSize * 3];
        for (int i = 0; i < d.nVertex; i++) {
            if (loc[i] != -1) {
                pos[loc[i] * 3] = (float) worldVerts[i * 3];
                pos[loc[i] * 3 + 1] = (float) worldVerts[i * 3 + 1];
                pos[loc[i] * 3 + 2] = (float) worldVerts[i * 3 + 2];
            }
        }
        int[] f = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            f[i] = loc[idx[i]];
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(f));
        mesh.updateBound();
        mesh.setStatic();

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Blue);
        mat.getAdditionalRenderState().setWireframe(true);

        Geometry g = new Geometry("map", mesh);
        g.setMaterial(mat);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        return g;
    }

    /** 该图 FieldMap 坐标点标记：center/startPoint/gate/warpGate。坐标已是 world 北正，与 mesh 同域。 */
    private Node buildFieldMapMarkers(FieldMap fm, SmdMapData data) {
        Node n = new Node("markers" + fm.ordinal());

        // 标记球半径：world 单位（相对地图尺度，0.1 scale 下视觉很小，用稍大值）
        float r = 120f;
        // 标记 y：取 (x,z) 附近地面高度；无地面则 y=0
        MarkerStyle center = marker(ColorRGBA.Yellow, r);
        MarkerStyle start = marker(ColorRGBA.Green, r);
        MarkerStyle gate = marker(ColorRGBA.Red, r);
        MarkerStyle warp = marker(ColorRGBA.Magenta, r);

        if (fm.center != null) {
            n.attachChild(makeMarker(center, fm.center[0], fm.center[1], data));
        }
        if (fm.startPoints != null) {
            for (int[] p : fm.startPoints) {
                n.attachChild(makeMarker(start, p[0], p[1], data));
            }
        }
        if (fm.gates != null) {
            for (FieldMap.Gate g : fm.gates) {
                n.attachChild(makeMarker(gate, g.x, g.z, data));
            }
        }
        if (fm.warpGates != null) {
            for (FieldMap.WarpGate w : fm.warpGates) {
                n.attachChild(makeMarker(warp, w.x, w.z, data));
            }
        }
        return n;
    }

    private static class MarkerStyle {
        final Material mat;
        final float radius;
        MarkerStyle(Material mat, float radius) { this.mat = mat; this.radius = radius; }
    }

    private MarkerStyle marker(ColorRGBA color, float radius) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setDepthWrite(false);
        return new MarkerStyle(mat, radius);
    }

    /** 在 (x,z) 放一个标记球，y 取地形地面高度。 */
    private Geometry makeMarker(MarkerStyle style, int x, int z, SmdMapData data) {
        float y = groundHeight(data, x, z);
        Sphere s = new Sphere(8, 8, style.radius);
        Geometry g = new Geometry("marker", s);
        g.setMaterial(style.mat);
        g.setLocalTranslation(x, y, z);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        return g;
    }

    /** 简单地形采样：在 (x,z) 附近找碰撞面最高点（world y）。无则 0。 */
    static float groundHeight(SmdMapData d, int wx, int wz) {
        double[] v = d.vertsWorldDouble();
        int[] idx = d.solidFaceIndices();
        float best = 0;
        for (int i = 0; i < idx.length; i += 3) {
            int a = idx[i], b = idx[i + 1], c = idx[i + 2];
            double ax = v[a * 3], az = v[a * 3 + 2];
            double bx = v[b * 3], bz = v[b * 3 + 2];
            double cx = v[c * 3], cz = v[c * 3 + 2];
            if (pointInTri2D(wx, wz, ax, az, bx, bz, cx, cz)) {
                double y = Math.max(v[a * 3 + 1], Math.max(v[b * 3 + 1], v[c * 3 + 1]));
                if (y > best) best = (float) y;
            }
        }
        return best;
    }

    static boolean pointInTri2D(double px, double pz, double ax, double az, double bx, double bz, double cx, double cz) {
        double d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
        double d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
        double d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
        boolean hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNeg && hasPos);
    }

    public static void main(String[] args) {
        String root = args.length > 0 ? args[0] : "E:/JPsTale/client";
        AppSettings settings = new AppSettings(true);
        settings.setResolution(1280, 720);
        settings.setSamples(4);
        MapWireframeApp app = new MapWireframeApp(root);
        app.setSettings(settings);
        app.start();
    }
}
