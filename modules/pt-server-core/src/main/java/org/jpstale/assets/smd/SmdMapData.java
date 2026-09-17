package org.jpstale.assets.smd;

/**
 * 瘦身版 SMD Stage 地图数据 —— 只保留服务端加载所需字段。
 * <p>
 * 复刻 jpstale-web {@code core/smd-parser.ts} 的解析语义（该实现经真实地图验证）。
 * 顶点以 <b>raw int</b> 保存（SMD 文件原值，不 ÷256、不轴翻转），读取层与文件逐位一致。
 */
public class SmdMapData {

    /** 顶点数 */
    public final int nVertex;
    /** 面数 */
    public final int nFace;

    /** 顶点坐标，每顶点 3 个 int（x,y,z raw），长度 = nVertex*3 */
    public final int[] verts;
    /** 每面 3 顶点索引，长度 = nFace*3（Uint16 源值） */
    public final int[] triIdx;
    /** 每面材质索引，长度 = nFace（Uint16 源值） */
    public final int[] faceMat;
    /** 每材质 meshState（meshState &amp; 1 == 1 才是碰撞面），长度 = 材质数 */
    public final int[] matMeshState;

    /** raw 世界坐标 AABB */
    public final int minX, maxX, minY, maxY, minZ, maxZ;

    public SmdMapData(int nVertex, int nFace, int[] verts, int[] triIdx, int[] faceMat, int[] matMeshState) {
        this.nVertex = nVertex;
        this.nFace = nFace;
        this.verts = verts;
        this.triIdx = triIdx;
        this.faceMat = faceMat;
        this.matMeshState = matMeshState;

        int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE;
        int mnY = Integer.MAX_VALUE, mxY = Integer.MIN_VALUE;
        int mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
        for (int i = 0; i < nVertex; i++) {
            int x = verts[i * 3], y = verts[i * 3 + 1], z = verts[i * 3 + 2];
            if (x < mnX) mnX = x;
            if (x > mxX) mxX = x;
            if (y < mnY) mnY = y;
            if (y > mxY) mxY = y;
            if (z < mnZ) mnZ = z;
            if (z > mxZ) mxZ = z;
        }
        this.minX = mnX;
        this.maxX = mxX;
        this.minY = mnY;
        this.maxY = mxY;
        this.minZ = mnZ;
        this.maxZ = mxZ;
    }

    /** 判断某面是否碰撞面（meshState &amp; 1 == 1） */
    public boolean isSolidFace(int fi) {
        if (fi < 0 || fi >= nFace) return false;
        int mat = faceMat[fi];
        return mat >= 0 && mat < matMeshState.length && (matMeshState[mat] & 1) != 0;
    }

    /**
     * 顶点转 world double（服务端运算域）：raw → (x/256, y/256, -z/256)，北正。
     * 长度 = nVertex*3。与 jpstale-web 渲染域（selfPos = -raw_z/256）同向，
     * 即客户端看到与服务端运算坐标系统一致。
     */
    public double[] vertsWorldDouble() {
        double[] out = new double[nVertex * 3];
        for (int i = 0; i < nVertex; i++) {
            out[i * 3] = verts[i * 3] / 256.0;
            out[i * 3 + 1] = verts[i * 3 + 1] / 256.0;
            out[i * 3 + 2] = -verts[i * 3 + 2] / 256.0;
        }
        return out;
    }

    /**
     * 碰撞面（meshState &amp; 1）三角形索引，长度 = 碰撞面数*3。
     * 下标指向 {@link #vertsWorldDouble()} 的顶点位（未压缩，可含未使用顶点）。
     */
    public int[] solidFaceIndices() {
        int cnt = 0;
        for (int fi = 0; fi < nFace; fi++) if (isSolidFace(fi)) cnt++;
        int[] out = new int[cnt * 3];
        int o = 0;
        for (int fi = 0; fi < nFace; fi++) {
            if (!isSolidFace(fi)) continue;
            out[o++] = triIdx[fi * 3];
            out[o++] = triIdx[fi * 3 + 1];
            out[o++] = triIdx[fi * 3 + 2];
        }
        return out;
    }
}
