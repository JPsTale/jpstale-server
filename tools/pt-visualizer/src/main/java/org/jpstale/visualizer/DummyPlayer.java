package org.jpstale.visualizer;

import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import org.jpstale.assets.smd.CollisionMesh;

/**
 * 玩家化身（复刻 jpstale-web dummy）：蓝线框盒（碰撞体 宽11×高21）+ 红前向线 + 绿 beacon。
 * <p>
 * 移动按 20FPS tick 结算：每 tick(50ms) 朝 moveAngle 走 stepPerTick(world，与前端每帧同量级)，
 * 遍历全部图 CollisionMesh 取"能走且结果 y 最高"（对应前端 findCurrentMap 归属最高地面）。
 * 逻辑坐标 = world 真实坐标，父节点(0.1 scale)只影响显示不影响本坐标。
 */
public class DummyPlayer extends Node {

    /** 每 tick 移动量（world 单位）。由档位换算得出（≈7.5 @82档），可调。 */
    private double stepPerTick = 7.5;
    /** 移动意图方向（弧度，0=+z 北）。WASD 相对相机设定。 */
    private double moveAngle = 0;
    private boolean wantMove = false;
    private boolean running = false;

    /** world 位置（与碰撞/网格同域，北正） */
    private double posX, posY, posZ;
    /** 所在图 mapId（移动后按实际命中更新） */
    private int mapId;
    /** 全部已加载图的碰撞网格（mapId 下标；null = 无图）。复刻前端"遍历所有图碰撞，取第一个能走"。 */
    private CollisionMesh[] meshes;

    /** 碰撞体参数（复刻 web dummy / 服务端 bodyWidth=11, bodyHeight=21） */
    private static final int BODY_WIDTH = 11;
    private static final int BODY_HEIGHT = 21;

    private final Geometry boxGeo;

    public DummyPlayer(com.jme3.asset.AssetManager assetManager) {
        // 蓝线框盒（碰撞体，宽=bodyWidth 高=bodyHeight，底部在 posY）
        Box box = new Box(BODY_WIDTH / 2f, BODY_HEIGHT / 2f, BODY_WIDTH / 2f);
        boxGeo = new Geometry("dummyBox", box);
        Material boxMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        boxMat.setColor("Color", ColorRGBA.Blue);
        boxMat.getAdditionalRenderState().setWireframe(true);
        boxGeo.setMaterial(boxMat);
        // 盒心抬到脚部之上：world y 是脚底，盒几何 y 需 + 高/2
        boxGeo.setLocalTranslation(0, BODY_HEIGHT / 2f, 0);
        attachChild(boxGeo);

        // 红前向线：局部沿 +z，随 Node 绕 Y 旋转（angle）而指向移动方向
        Geometry line = new Geometry("fwdLine", lineMesh(0, 8, 0, 0, 8, BODY_HEIGHT + 12f));
        Material lineMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        lineMat.setColor("Color", ColorRGBA.Red);
        lineMat.getAdditionalRenderState().setLineWidth(2f);
        line.setMaterial(lineMat);
        attachChild(line);

        // 绿 beacon：顶部细长竖杆（Box，高沿 Y 站着），便于远观定位
        Geometry beacon = new Geometry("beacon", new Box(0.5f, 30f, 0.5f));
        Material beaconMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        beaconMat.setColor("Color", ColorRGBA.Green);
        beaconMat.getAdditionalRenderState().setWireframe(true);
        beacon.setMaterial(beaconMat);
        beacon.setLocalTranslation(0, BODY_HEIGHT + 30f, 0);
        attachChild(beacon);
    }

    private Mesh lineMesh(float x1, float y1, float z1, float x2, float y2, float z2) {
        Mesh m = new Mesh();
        float[] verts = {x1, y1, z1, x2, y2, z2};
        m.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3, verts);
        m.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3,
            com.jme3.util.BufferUtils.createIntBuffer(new int[]{0, 1}));
        m.updateBound();
        m.setMode(com.jme3.scene.Mesh.Mode.Lines);
        return m;
    }

    public void setPosition(double x, double y, double z) {
        this.posX = x; this.posY = y; this.posZ = z;
        updateNode();
    }

    /** 设置全部图碰撞网格（mapId 下标），并初始定位到某图。 */
    public void setMeshes(CollisionMesh[] meshes, int startMapId, double x, double y, double z) {
        this.meshes = meshes;
        this.mapId = startMapId;
        setPosition(x, y, z);
    }

    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public int getMapId() { return mapId; }
    public boolean isRunning() { return running; }
    public boolean isWantMove() { return wantMove; }

    public void setStepPerTick(double step) { this.stepPerTick = step; }
    public double getStepPerTick() { return stepPerTick; }

    public void setWantMove(boolean w) { this.wantMove = w; }
    public void setRunning(boolean r) { this.running = r; }
    public void setMoveAngle(double a) {
        this.moveAngle = a;
        // 红前向线局部沿 +z；绕 Y 转 angle 使其指向移动方向 (sin, 0, cos)
        setLocalRotation(new Quaternion().fromAngleAxis((float) a, Vector3f.UNIT_Y));
    }
    public double getMoveAngle() { return moveAngle; }

    /**
     * 每 tick 结算一次：朝 moveAngle 走 stepPerTick（= step_f×3，20FPS 大步，world/s 与前端一致）。
     * 大步用 checkNextMoveCcd（CCD 拆 ≤5.5 子步防穿桥）；若大步被所有图挡（常见跨图边界，
     * 单图网格无法覆盖整步），退化小步渐进逐格换图。返回实际位移。
     */
    public double tick() {
        if (!wantMove || meshes == null) return 0;
        double ox = posX, oz = posZ;
        if (tryStep(stepPerTick)) {
            updateNode();
            return Math.hypot(posX - ox, posZ - oz);
        }
        // 大步被挡 → 小步渐进（≤ CCD 子步），每小步多图取最高
        double small = Math.min(CollisionMesh.CCD_MAX_STEP, stepPerTick);
        int guard = 0;
        while (guard++ < 1000) {
            if (!tryStep(small)) break;
            if (Math.hypot(posX - ox, posZ - oz) >= stepPerTick - 1e-9) break;
        }
        updateNode();
        return Math.hypot(posX - ox, posZ - oz);
    }

    /**
     * 尝试走一步 dist：遍历全部图碰撞网格，取所有能走的图中"结果 y 最高"者。
     * 理由：桥口角色脚下可能有多个图的地面（village-2 桥面 vs fore-1 桥下低地），
     * 若只取"第一个能走"，低序号图(如 fore-1)的低地面会先放行导致掉桥；
     * 取 y 最高者 = 角色站在最高的可站立面上（对应前端 findCurrentMap 归属最高地面）。
     * 复刻前端"多 stage 遍历"，只是把"第一个"换成"最高"，避免跨图误掉。
     */
    private boolean tryStep(double dist) {
        double yBefore = posY;
        CollisionMesh.MoveResult best = null;
        int bestMap = -1;
        for (int i = 0; i < meshes.length; i++) {
            CollisionMesh cm = meshes[i];
            if (cm == null) continue;
            CollisionMesh.MoveResult r = cm.checkNextMoveCcd(posX, posY, posZ, moveAngle, dist, BODY_WIDTH);
            if (!r.collision) {
                if (best == null || r.y > best.y) {
                    best = r;
                    bestMap = i;
                }
            }
        }
        if (best == null) return false;
        // 诊断：明显下落（>8 world）打印该点每图地面，定位是否"走空掉下"
        if (best.y < yBefore - 8.0) {
            StringBuilder sb = new StringBuilder("[drop] map" + bestMap + " 移动前y=" + yBefore
                + " 结果y=" + best.y + " pos=(" + (int) best.x + "," + (int) best.z + ") 各图地面:");
            for (int k = 0; k < meshes.length; k++) {
                if (meshes[k] == null) continue;
                Double h = meshes[k].getFloorHeight(best.x, best.z, Double.MAX_VALUE);
                sb.append(" m").append(k).append("=").append(h == null ? "无" : String.format("%.1f", h));
            }
            System.out.println(sb);
        }
        posX = best.x; posY = best.y; posZ = best.z;
        mapId = bestMap;
        return true;
    }

    private void updateNode() {
        setLocalTranslation((float) posX, (float) posY, (float) posZ);
    }
}
