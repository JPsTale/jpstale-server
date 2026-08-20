package org.jpstale.server.game.tool;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.LittleEndien;
import org.jpstale.assets.plugins.smd.stage.Stage;
import org.jpstale.assets.utils.SceneBuilder;
import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 离线渲染：把每张地图的 .smd 碰撞网格光栅化成 PNG（低分辨率背景图）。
 * 输出到 web-server 的 static/spawn-debug/maps/，前端按 AABB 定位绘制。
 * <p>
 * 坐标用原始世界坐标（与 AABB/玩家坐标一致）：MapMesh 顶点是翻转坐标（x=-raw,z=-raw），
 * 渲染时取反得原始坐标。
 */
public class RenderMapPng {

    /** 输出图像最大边长（1024 = 512 的两倍分辨率） */
    private static final int MAX_PX = 1024;
    /** 地形三角色 */
    private static final Color LAND = new Color(0x2d3d33);
    /** 线框色 */
    private static final Color LINE = new Color(0x4a5a4e);

    public static void main(String[] args) throws Exception {
        String smdRoot = args.length > 0 ? args[0] : "/data/PristonTale/exm/run";
        String outDir = args.length > 1 ? args[1]
            : "/data/PristonTale/src/jpstale-server/pt-web-server/src/main/resources/static/spawn-debug/maps";
        File dir = new File(outDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        for (FieldMap fm : FieldMap.values()) {
            File f = new File(smdRoot, "field/" + fm.smd);
            if (!f.exists()) {
                System.out.println("skip (no smd): map" + fm.ordinal() + " " + fm.smd);
                continue;
            }
            MapMesh mesh = MapMesh.load(f);
            if (mesh == null) {
                System.out.println("skip (no mesh): map" + fm.ordinal());
                continue;
            }
            // 以 .smd 头部 RECT 为权威 AABB（与前端 map.aabbs / 服务器定位一致），
            // 不用 mesh 顶点范围（部分图顶点范围 < RECT，会导致内容偏移/拉伸）
            int[] rect = readSmdRect(smdRoot, fm.smd);
            if (rect == null) {
                System.out.println("skip (no rect): map" + fm.ordinal());
                continue;
            }
            BufferedImage img = render(mesh, rect);
            File out = new File(dir, "map" + fm.ordinal() + ".png");
            ImageIO.write(img, "png", out);
            System.out.println("map" + fm.ordinal() + " -> " + out.getName()
                + " " + img.getWidth() + "x" + img.getHeight()
                + " (faces=" + mesh.getFaceCount() + ")");
        }
        System.out.println("done -> " + dir.getAbsolutePath());
    }

    /** 读取 .smd 头部 RECT（与 MapRegionService.readSmdRect 同逻辑），返回 [xMin,xMax,zMin,zMax]。 */
    private static int[] readSmdRect(String smdRoot, String smdRel) {
        File file = new File(smdRoot, "field/" + smdRel);
        if (!file.exists()) {
            return null;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(262800);
            byte[] buf = new byte[16];
            raf.readFully(buf);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int left = bb.getInt();
            int top = bb.getInt();
            int right = bb.getInt();
            int bottom = bb.getInt();
            return new int[]{left / 256, right / 256, top / 256, bottom / 256};
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * 光栅化网格到 BufferedImage（基于 TestGridMesh.drawBackground 的三角形绘制思路）。
     * 坐标取反还原为原始世界坐标。
     */
    private static BufferedImage render(MapMesh mesh, int[] rect) {
        // 使用 .smd 头部 RECT 作为 AABB，与前端 map.aabbs 定位一致
        float minX = rect[0], maxX = rect[1];
        float minZ = rect[2], maxZ = rect[3];
        float w = maxX - minX;
        float h = maxZ - minZ;

        float scale = MAX_PX / Math.max(w, h);
        int iw = Math.max(8, (int) (w * scale));
        int ih = Math.max(8, (int) (h * scale));

        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // 清空为全透明，使 PNG 背景透明（前端透出 canvas 底色）
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, iw, ih);
        g.setComposite(AlphaComposite.SrcOver);

        float[] verts = mesh.getVertices();
        int[] idx = mesh.getIndices();
        int fCount = idx.length / 3;

        // 地形填充（半透明面）
        g.setColor(new Color(LAND.getRed(), LAND.getGreen(), LAND.getBlue(), 200));
        Polygon tri = new Polygon();
        for (int i = 0; i < fCount; i++) {
            tri.reset();
            for (int j = 0; j < 3; j++) {
                int vi = idx[i * 3 + j];
                float x = -verts[vi * 3 + 2];  // GL_z -> DX_x
                float z = -verts[vi * 3];      // GL_x -> DX_z
                int px = (int) ((x - minX) * scale);
                // z 正方向为北，北在上：py 随 z 增大而减小
                int py = clampPy((maxZ - z) * scale, ih);
                tri.addPoint(px, py);
            }
            g.fill(tri);
        }

        // 线框
        g.setColor(LINE);
        for (int i = 0; i < fCount; i++) {
            tri.reset();
            for (int j = 0; j < 3; j++) {
                int vi = idx[i * 3 + j];
                float x = -verts[vi * 3 + 2];  // GL_z -> DX_x
                float z = -verts[vi * 3];      // GL_x -> DX_z
                int px = (int) ((x - minX) * scale);
                int py = clampPy((maxZ - z) * scale, ih);
                tri.addPoint(px, py);
            }
            g.draw(tri);
        }
        g.dispose();
        return img;
    }

    /** 将 z 映射到像素 y：北(z 大)在上、南(z 小)在下，clamp 到 [0, ih-1]。 */
    private static int clampPy(float py, int ih) {
        int v = (int) py;
        if (v < 0) return 0;
        if (v >= ih) return ih - 1;
        return v;
    }
}
