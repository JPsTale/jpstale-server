package org.jpstale.visualizer;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.ChaseCamera;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferUtils;
import org.jpstale.assets.smd.CollisionMesh;
import org.jpstale.assets.smd.SmdMapData;
import org.jpstale.assets.smd.SmdMapLoader;
import org.jpstale.server.game.model.FieldCatalog;
import org.jpstale.server.game.model.FieldInfo;
import org.jpstale.server.game.model.FieldInfo.FieldGate;
import org.jpstale.server.game.model.FieldInfo.WarpGate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;

/**
 * jME3 wireframe 可视化 + 玩家化身碰撞验证：
 * 63 图 wireframe + fields.json 坐标点 + DB spawn box；
 * 按 20FPS tick 结算移动，档位 1~100（EU 公式）调步长，人工验证碰撞与 collision.ts 一致。
 *
 * 运行：
 *   mvn -f pt-visualizer/pom.xml compile exec:java \
 *       -Dexec.mainClass=org.jpstale.visualizer.MapWireframeApp -Dexec.args="E:/JPsTale/client"
 *
 * 坐标域：world (x/256, y/256, -z/256) 北正，与 jpstale-web 渲染域同向。
 */
public class MapWireframeApp extends SimpleApplication {

    private final String smdRoot;

    private final SmdMapData[] mapData = new SmdMapData[FieldCatalog.get().maxId() + 1];
    /** 每图碰撞网格（懒建） */
    private final CollisionMesh[] collisionMesh = new CollisionMesh[FieldCatalog.get().maxId() + 1];

    /** T 键调试：高亮每帧参与检测的三角形（按图分色） */
    private boolean trisDebugOn = false;
    private final Node trisDebugNode = new Node("trisDebug");

    private DummyPlayer player;
    private Node maps;
    private ChaseCamera chaseCam;
    private boolean chaseMode = false;

    // 输入状态
    private boolean keyW, keyA, keyS, keyD, shift;
    private int level = 82; // 档位 1~100

    // 20FPS tick 累加
    private float tickAcc = 0f;
    private static final float TICK_MS = 50f;

    // HUD
    private BitmapText hud;

    public MapWireframeApp(String smdRoot) {
        this.smdRoot = smdRoot;
    }

    @Override
    public void simpleInitApp() {
        flyCam.setMoveSpeed(2000f);
        cam.setFrustumFar(4000f);

        maps = new Node("maps");
        maps.setLocalScale(0.1f);
        rootNode.attachChild(maps);

        loadAllMaps();

        maps.attachChild(trisDebugNode);
        trisDebugNode.setCullHint(Spatial.CullHint.Always);

        loadDbSpawns();

        buildHud();
        initInput();

        // 玩家起点：village-2 (field id=3) 出生点（fields.json）
        FieldInfo v2 = FieldCatalog.get().get(3);
        SmdMapData d3 = mapData[3];
        java.util.List<int[]> pts = v2.getStartPoints();
        double[] sp = pts != null && !pts.isEmpty() ? toDouble(pts.get(0)) : toDouble(v2.getCenter());
        double sx = sp[0], sz = sp[1];
        double sy = groundHeight(d3, (int) sx, (int) sz);

        player = new DummyPlayer(assetManager);
        player.setName("player");
        maps.attachChild(player);
        player.setMeshes(allCollisionMeshes(), 3, sx, sy, sz);
        applyLevel();

        // ChaseCamera（跟随 player）；启动默认 Fly，按 F 切换
        chaseCam = new ChaseCamera(cam, player, inputManager);
        chaseCam.setMaxDistance(400f);
        chaseCam.setMinDistance(10f);
        chaseCam.setDefaultDistance(120f);
        chaseCam.setDragToRotate(true);
        chaseCam.setEnabled(false);
        flyCam.setEnabled(true);

        setCamLocationNear((float) sx, (float) sz);
        System.out.println("player start map3 (" + sx + ", " + sy + ", " + sz + ")");
        System.out.println("controls: WASD 移动 Shift=跑 | F 切 Chase/Fly | [ / ] 调档 1~100 | 鼠标拖拽转视角");
    }

    private void loadAllMaps() {
        int loaded = 0;
        for (FieldInfo fm : FieldCatalog.get().list()) {
            int mapId = fm.getId();
            Path file = Path.of(smdRoot, "field", fm.getModel());
            if (!Files.exists(file)) {
                System.out.println("skip (no file): map" + mapId + " " + fm.getModel());
                continue;
            }
            try {
                SmdMapData data = SmdMapLoader.load(file);
                if (data.nFace <= 0) {
                    System.out.println("skip (empty): map" + mapId + " " + fm.getModel());
                    continue;
                }
                mapData[mapId] = data;

                Geometry g = buildCollisionGeometry(data);
                g.setName("map" + mapId);
                maps.attachChild(g);

                Node markers = buildFieldMapMarkers(fm, data);
                maps.attachChild(markers);

                loaded++;
                System.out.println("map" + mapId + " " + fm.getModel()
                    + " verts=" + data.nVertex + " solidFaces=" + solidFaceCount(data));
            } catch (Exception e) {
                System.out.println("error map" + mapId + " " + fm.getModel() + ": " + e);
            }
        }
        System.out.println("Loaded " + loaded + "/" + FieldCatalog.get().list().size() + " maps");
    }

    private static double[] toDouble(int[] a) {
        return new double[]{a[0], a[1]};
    }

    private CollisionMesh collisionMesh(int mapId) {
        if (collisionMesh[mapId] == null && mapData[mapId] != null) {
            collisionMesh[mapId] = CollisionMesh.fromSmd(mapData[mapId]);
        }
        return collisionMesh[mapId];
    }

    /** 全部已加载图的碰撞网格数组（mapId 下标，无图为 null），懒构建。 */
    private CollisionMesh[] allCollisionMeshes() {
        CollisionMesh[] arr = new CollisionMesh[collisionMesh.length];
        for (int i = 0; i < arr.length; i++) {
            if (mapData[i] != null) arr[i] = collisionMesh(i);
        }
        return arr;
    }

    // ===================== 输入 =====================

    private static final String M_FWD = "fwd", M_BACK = "back", M_LEFT = "left", M_RIGHT = "right",
        M_RUN = "run", M_CHASE = "chase", M_LEVEL_UP = "lvlUp", M_LEVEL_DN = "lvlDn", M_TELEPORT = "tp",
        M_TRIS = "tris";

    private void initInput() {
        inputManager.addMapping(M_FWD, new KeyTrigger(KeyInput.KEY_W));
        inputManager.addMapping(M_BACK, new KeyTrigger(KeyInput.KEY_S));
        inputManager.addMapping(M_LEFT, new KeyTrigger(KeyInput.KEY_A));
        inputManager.addMapping(M_RIGHT, new KeyTrigger(KeyInput.KEY_D));
        inputManager.addMapping(M_RUN, new KeyTrigger(KeyInput.KEY_LSHIFT));
        inputManager.addMapping(M_CHASE, new KeyTrigger(KeyInput.KEY_F));
        inputManager.addMapping(M_LEVEL_UP, new KeyTrigger(KeyInput.KEY_RBRACKET));
        inputManager.addMapping(M_LEVEL_DN, new KeyTrigger(KeyInput.KEY_LBRACKET));
        inputManager.addMapping(M_TELEPORT, new KeyTrigger(KeyInput.KEY_U));
        inputManager.addMapping(M_TRIS, new KeyTrigger(KeyInput.KEY_T));

        ActionListener al = (name, isPressed, tpf) -> {
            switch (name) {
                case M_FWD -> keyW = isPressed;
                case M_BACK -> keyS = isPressed;
                case M_LEFT -> keyA = isPressed;
                case M_RIGHT -> keyD = isPressed;
                case M_RUN -> shift = isPressed;
                case M_CHASE -> {
                    if (isPressed) toggleChase();
                }
                case M_LEVEL_UP -> {
                    if (isPressed) { level = Math.min(100, level + 1); applyLevel(); }
                }
                case M_LEVEL_DN -> {
                    if (isPressed) { level = Math.max(1, level - 1); applyLevel(); }
                }
                case M_TELEPORT -> {
                    if (isPressed) teleportUp();
                }
                case M_TRIS -> {
                    if (isPressed) toggleTrisDebug();
                }
            }
        };
        inputManager.addListener(al, M_FWD, M_BACK, M_LEFT, M_RIGHT, M_RUN, M_CHASE, M_LEVEL_UP, M_LEVEL_DN, M_TELEPORT, M_TRIS);
    }

    private void toggleChase() {
        chaseMode = !chaseMode;
        chaseCam.setEnabled(chaseMode);
        flyCam.setEnabled(!chaseMode);
        System.out.println("camera: " + (chaseMode ? "Chase" : "Fly"));
    }

    /** U 键：dummy y+100，摆脱卡住/陷落。 */
    private void teleportUp() {
        player.setPosition(player.getPosX(), player.getPosY() + 100, player.getPosZ());
        updateHud();
        System.out.println("[u] y+100 -> " + player.getPosY());
    }

    // ===================== 三角形调试（T 键）=====================

    private static final ColorRGBA[] TRIS_COLORS = {
        ColorRGBA.Cyan, ColorRGBA.Orange, ColorRGBA.Pink, ColorRGBA.White, ColorRGBA.Yellow,
    };

    private void toggleTrisDebug() {
        trisDebugOn = !trisDebugOn;
        trisDebugNode.setCullHint(trisDebugOn
            ? com.jme3.scene.Spatial.CullHint.Inherit
            : com.jme3.scene.Spatial.CullHint.Always);
        System.out.println("[t] 三角形调试 " + (trisDebugOn ? "开" : "关"));
        if (trisDebugOn) refreshTrisDebug();
    }

    /** 重建：把每张图在角色邻近的碰撞三角形单独画出（按图分色）。 */
    private void refreshTrisDebug() {
        trisDebugNode.detachAllChildren();
        if (!trisDebugOn || player == null) return;
        double px = player.getPosX(), pz = player.getPosZ();
        StringBuilder sb = new StringBuilder("[t] pos=(" + (int) px + "," + (int) pz + ") 每图邻近:");
        for (int i = 0; i < collisionMesh.length; i++) {
            CollisionMesh cm = collisionMesh[i];
            if (cm == null) continue;
            List<CollisionMesh.Tri> tris = cm.nearbyTriangles(px, pz);
            sb.append(" m").append(i).append("=").append(tris.size());
            if (tris.isEmpty()) continue;
            Geometry g = buildTrisSolidGeometry(tris, TRIS_COLORS[i % TRIS_COLORS.length]);
            g.setName("trisDebug_map" + i);
            trisDebugNode.attachChild(g);
        }
        System.out.println(sb);
    }

    /** 三角形 → 实心半透明三角面 mesh（Triangles 模式），叠加在蓝色 wireframe 上醒目可辨。 */
    private Geometry buildTrisSolidGeometry(java.util.List<CollisionMesh.Tri> tris, ColorRGBA color) {
        float[] pos = new float[tris.size() * 3 * 3];
        int[] idx = new int[tris.size() * 3];
        int v = 0;
        for (int t = 0; t < tris.size(); t++) {
            CollisionMesh.Tri tr = tris.get(t);
            pos[v * 3] = (float) tr.x1; pos[v * 3 + 1] = (float) tr.y1; pos[v * 3 + 2] = (float) tr.z1;
            idx[t * 3] = v++;
            pos[v * 3] = (float) tr.x2; pos[v * 3 + 1] = (float) tr.y2; pos[v * 3 + 2] = (float) tr.z2;
            idx[t * 3 + 1] = v++;
            pos[v * 3] = (float) tr.x3; pos[v * 3 + 1] = (float) tr.y3; pos[v * 3 + 2] = (float) tr.z3;
            idx[t * 3 + 2] = v++;
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        m.setMode(Mesh.Mode.Triangles);
        m.updateBound();
        m.setStatic();

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", new ColorRGBA(color.r, color.g, color.b, 0.5f));
        mat.getAdditionalRenderState().setDepthWrite(false);
        Geometry g = new Geometry("trisDebug", m);
        g.setMaterial(mat);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        return g;
    }

    /** 三角形 → Line 线框 mesh（每三角形 3 条边，world 坐标）。 */
    private Geometry buildTrisLineGeometry(java.util.List<CollisionMesh.Tri> tris, ColorRGBA color) {
        float[] pos = new float[tris.size() * 3 * 2 * 3];
        int[] idx = new int[tris.size() * 3 * 2];
        int v = 0, e = 0;
        for (CollisionMesh.Tri t : tris) {
            float[] p = {
                (float) t.x1, (float) t.y1, (float) t.z1,
                (float) t.x2, (float) t.y2, (float) t.z2,
                (float) t.x3, (float) t.y3, (float) t.z3,
            };
            int[][] edges = {{0, 1}, {1, 2}, {2, 0}};
            for (int[] ed : edges) {
                pos[v * 3] = p[ed[0] * 3];
                pos[v * 3 + 1] = p[ed[0] * 3 + 1];
                pos[v * 3 + 2] = p[ed[0] * 3 + 2];
                idx[e++] = v++;
                pos[v * 3] = p[ed[1] * 3];
                pos[v * 3 + 1] = p[ed[1] * 3 + 1];
                pos[v * 3 + 2] = p[ed[1] * 3 + 2];
                idx[e++] = v++;
            }
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        m.setMode(Mesh.Mode.Lines);
        m.updateBound();
        m.setStatic();

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        Geometry g = new Geometry("trisDebug", m);
        g.setMaterial(mat);
        return g;
    }

    // ===================== 档位换算 =====================

    /** EU 档位 → 60fps 语义每帧位移（world）。与 jpstale-web speedLevelToRunStep 一致。 */
    static double levelToStepF(int cnt) {
        return (((long) cnt * 10 + 250) * 460 >> 8) / 256.0;
    }

    /** 20FPS 每 tick 步长 = step_f × 60/20 = step_f × 3（保持与前端 60fps 相同的 world/s）。 */
    static double levelToStepPerTick(int cnt) {
        return levelToStepF(cnt) * 3.0;
    }

    private void applyLevel() {
        // 每 tick 走 step_f×3（world/s 与前端一致）；大步由 CCD 在 checkNextMoveCcd 内拆子步防穿
        player.setStepPerTick(levelToStepPerTick(level));
        updateHud();
    }

    // ===================== 主循环 tick =====================

    @Override
    public void simpleUpdate(float tpf) {
        updateInput();
        tickAcc += tpf * 1000f;
        while (tickAcc >= TICK_MS) {
            tickAcc -= TICK_MS;
            playerTick();
        }
    }

    private void updateInput() {
        // 相对相机水平方向确定移动意图
        double fwd = 0, side = 0;
        if (keyW) fwd += 1;
        if (keyS) fwd -= 1;
        if (keyD) side -= 1;
        if (keyA) side += 1;
        boolean want = (fwd != 0 || side != 0);
        player.setWantMove(want);
        player.setRunning(shift);
        if (!want) return;

        // 相机朝向的水平分量作为"前"
        Vector3f camDir = cam.getDirection();
        double fx = camDir.x, fz = camDir.z;
        double flen = Math.hypot(fx, fz);
        if (flen < 1e-6) { fx = 0; fz = 1; flen = 1; }
        fx /= flen; fz /= flen;
        // 右 = 前 × up(0,1,0) 叉积 → (fz, 0, -fx) 归一
        double rx = fz, rz = -fx;
        double dx = fx * fwd + rx * side;
        double dz = fz * fwd + rz * side;
        player.setMoveAngle(Math.atan2(dx, dz));
    }

    private int trisRefreshCounter = 0;

    private void playerTick() {
        if (player.isWantMove()) {
            player.tick();
            updateHud();
        }
        // 三角形调试跟随角色位置周期刷新（每 5 tick ≈ 250ms）
        if (trisDebugOn && ++trisRefreshCounter >= 5) {
            trisRefreshCounter = 0;
            refreshTrisDebug();
        }
    }

    // ===================== HUD =====================

    private void buildHud() {
        hud = new BitmapText(guiFont, false);
        hud.setSize(14);
        hud.setColor(ColorRGBA.White);
        hud.setLocalTranslation(8, cam.getHeight() - 20, 0);
        guiNode.attachChild(hud);
    }

    private void updateHud() {
        double stepF = levelToStepF(level);
        double stepPerTick = player.getStepPerTick();
        double perSec = stepF * 60;
        boolean blocked = false; // tick 被挡单独标志（简单起见每次 tick 更新）
        String txt = String.format(
            "档 %d  MoveSpeed=%d\nstep_f=%.2f  step/tick(20fps)=%.2f  %.1f world/s\n" +
            "pos=(%.1f, %.1f, %.1f)  map=%d  %s",
            level, level * 10 + 250, stepF, stepPerTick, perSec,
            player.getPosX(), player.getPosY(), player.getPosZ(), player.getMapId(),
            chaseMode ? "ChaseCam" : "FlyCam");
        hud.setText(txt);
    }

    private void setCamLocationNear(float x, float z) {
        // 相机放起点附近上方，俯视
        cam.setLocation(new Vector3f(x + 20f, 200f, z + 60f));
        cam.lookAt(new Vector3f(x, 0, z), Vector3f.UNIT_Y);
    }

    // ===================== 渲染构建（wireframe / markers / spawn） =====================

    static int solidFaceCount(SmdMapData d) {
        int c = 0;
        for (int fi = 0; fi < d.nFace; fi++) if (d.isSolidFace(fi)) c++;
        return c;
    }

    static final String DB_URL = "jdbc:postgresql://192.168.31.10:5432/pristontale";
    static final String DB_USER = "sa";
    static final String DB_PASSWORD = "632514Go";

    private void loadDbSpawns() {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Red);
        float half = 30f;
        int count = 0, skipped = 0;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("SELECT stage, x, z FROM gamedb.mapspawnpoint");
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
                Geometry g = new Geometry("spawn", new Box(half, half, half));
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

    private Geometry buildCollisionGeometry(SmdMapData d) {
        double[] worldVerts = d.vertsWorldDouble();
        int[] idx = d.solidFaceIndices();

        int[] loc = new int[d.nVertex];
        Arrays.fill(loc, -1);
        int vSize = 0;
        for (int v : idx) {
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

    private Node buildFieldMapMarkers(FieldInfo fm, SmdMapData data) {
        Node n = new Node("markers" + fm.getId());
        float r = 120f;
        MarkerStyle center = marker(ColorRGBA.Yellow, r);
        MarkerStyle start = marker(ColorRGBA.Green, r);
        MarkerStyle gate = marker(ColorRGBA.Red, r);
        MarkerStyle warp = marker(ColorRGBA.Magenta, r);

        if (fm.getCenter() != null) n.attachChild(makeMarker(center, fm.getCenter()[0], fm.getCenter()[1], data));
        if (fm.getStartPoints() != null) {
            for (int[] p : fm.getStartPoints()) n.attachChild(makeMarker(start, p[0], p[1], data));
        }
        if (fm.getFieldGates() != null) {
            for (FieldGate g : fm.getFieldGates()) n.attachChild(makeMarker(gate, g.getX(), g.getZ(), data));
        }
        if (fm.getWarpGates() != null) {
            for (WarpGate w : fm.getWarpGates()) n.attachChild(makeMarker(warp, w.getX(), w.getZ(), data));
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

    private Geometry makeMarker(MarkerStyle style, int x, int z, SmdMapData data) {
        float y = groundHeight(data, x, z);
        Geometry g = new Geometry("marker", new Sphere(8, 8, style.radius));
        g.setMaterial(style.mat);
        g.setLocalTranslation(x, y, z);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        return g;
    }

    static float groundHeight(SmdMapData d, int wx, int wz) {
        if (d == null) return 0;
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
