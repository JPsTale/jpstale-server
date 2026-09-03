package org.jpstale.server.game.tool;

import org.jpstale.server.game.model.FieldMap;
import org.jpstale.server.game.model.MapMesh;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 离线渲染：把每张地图的 .smd 碰撞网格光栅化成 PNG（低分辨率背景图）。
 * 输出到 pt-web-server static/spawn-debug/maps/，前端按 AABB 定位绘制。
 * <p>
 * 坐标域 world double（(rawX/256, rawY/256, -rawZ/256)，北正），与渲染/玩家/碰撞同域，
 * 顶点直接取用，无取反/交换。
 */
public class RenderMapPng {

    /** 输出图像最大边长 */
    private static final int MAX_PX = 1024;
    private static final Color LAND = new Color(0x2d3d33);
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
            BufferedImage img = render(mesh);
            File out = new File(dir, "map" + fm.ordinal() + ".png");
            ImageIO.write(img, "png", out);
            System.out.println("map" + fm.ordinal() + " -> " + out.getName()
                + " " + img.getWidth() + "x" + img.getHeight()
                + " (faces=" + mesh.getFaceCount() + ")");
        }
        System.out.println("done -> " + dir.getAbsolutePath());
    }

    /**
     * 光栅化网格到 BufferedImage（world double 顶点，北正 z 向上）。
     */
    private static BufferedImage render(MapMesh mesh) {
        double minX = mesh.getMinX(), maxX = mesh.getMaxX();
        double minZ = mesh.getMinZ(), maxZ = mesh.getMaxZ();
        double w = maxX - minX;
        double h = maxZ - minZ;
        if (w <= 0 || h <= 0) {
            return new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        }

        double scale = MAX_PX / Math.max(w, h);
        int iw = Math.max(8, (int) (w * scale));
        int ih = Math.max(8, (int) (h * scale));

        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, iw, ih);
        g.setComposite(AlphaComposite.SrcOver);

        double[] verts = mesh.getVertices();
        int[] idx = mesh.getIndices();
        int fCount = idx.length / 3;

        // 地形填充（半透明面）
        g.setColor(new Color(LAND.getRed(), LAND.getGreen(), LAND.getBlue(), 200));
        Polygon tri = new Polygon();
        for (int i = 0; i < fCount; i++) {
            tri.reset();
            for (int j = 0; j < 3; j++) {
                int vi = idx[i * 3 + j];
                double x = verts[vi * 3];
                double z = verts[vi * 3 + 2]; // 北正：z 大在上
                int px = (int) ((x - minX) * scale);
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
                double x = verts[vi * 3];
                double z = verts[vi * 3 + 2];
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
    private static int clampPy(double py, int ih) {
        int v = (int) py;
        if (v < 0) return 0;
        if (v >= ih) return ih - 1;
        return v;
    }
}
