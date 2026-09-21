package org.jpstale.assets.smd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SMD Stage 二进制解析器 —— Java 版，逐偏移复刻 jpstale-web {@code core/smd-parser.ts}。
 * <p>
 * 只解析服务端需要的字段：材质 meshState（筛碰撞面）、顶点、面索引、每面材质。
 * 不解析纹理路径 / UV / 灯光 / 顶点色。不依赖 jME3。
 * <p>
 * 顶点坐标为 <b>raw int</b>（SMD 文件原值，无 ÷256、无轴翻转），
 * 与 SMD 文件逐位一致。上层自行决定是否转 world 坐标。
 * <p>
 * 文件布局（与 smd-parser.ts 一致）：
 * <pre>
 *   [0,556)        SmdFileHeader（跳过）
 *   [556,+)        smLegacySTAGE3D: Head(4) + StageArea[256][256](262144) + 指针区(48)
 *                   → nVertex(4) nFace(4) nTexLink(4) nLight(4) + 其余(48)
 *   材质组: 头部(88) 内含 MaterialCount @+8，随后每材质 [320 字节头 + 纹理名区]
 *   顶点区: 每顶点 28 字节（xyz @+8/+12/+16）
 *   面区:   每面 28 字节（3 顶点索引 @+8/+10/+12 uint16，材质 @+14 uint16）
 * </pre>
 */
public final class SmdMapLoader {

    private static final int SMD_HEADER_SIZE = 556;
    private static final int STAGE_AREA = 262144;
    private static final int TAIL_AFTER_COUNTS = 48;
    private static final int MATERIAL_HEADER = 88;
    private static final int MATERIAL_RECORD = 320;
    private static final int VERTEX_RECORD = 28;
    private static final int FACE_RECORD = 28;

    private SmdMapLoader() {
    }

    public static SmdMapData load(Path file) throws IOException {
        byte[] buf = Files.readAllBytes(file);
        ByteBuffer dv = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

        StringBuilder hdr = new StringBuilder();
        for (int i = 0; i < 24 && i < buf.length; i++) {
            if (buf[i] != 0) hdr.append((char) (buf[i] & 0xff));
        }
        if (!hdr.toString().startsWith("SMD Stage data")) {
            throw new IOException("Invalid SMD: " + hdr);
        }
        if (buf.length < SMD_HEADER_SIZE) {
            throw new IOException("SMD too small: " + buf.length);
        }

        int off = SMD_HEADER_SIZE;

        // smLegacySTAGE3D: Head(4) + StageArea(262144)
        off += 4 + STAGE_AREA;

        // Tail: 12 个指针/int（跳过全部）
        off += 48;

        int nVertex = dv.getInt(off); off += 4;
        int nFace = dv.getInt(off); off += 4;
        int nTexLink = dv.getInt(off); off += 4;
        int nLight = dv.getInt(off); off += 4;
        off += TAIL_AFTER_COUNTS;

        // 材质组头部：MaterialCount @+8，随后每材质 320 头 + 纹理名区
        int materialCount = dv.getInt(off + 8);
        off += MATERIAL_HEADER;

        int[] matMeshState = new int[Math.max(materialCount, 0)];
        for (int i = 0; i < materialCount; i++) {
            int inUseRaw = dv.getInt(off);
            int meshState = dv.getInt(off + 168);
            int texCounter = dv.getInt(off + 4);
            int animTexCounter = dv.getInt(off + 304);
            off += MATERIAL_RECORD;

            if (inUseRaw != 0 && off + 4 <= buf.length) {
                int strLen = dv.getInt(off);
                off += 4;
                int strEnd = off + (strLen > 0 && strLen < 100000 ? strLen : 0);
                int guard = 0;
                while (off < strEnd && guard < 100000) {
                    int nl = 0;
                    while (off + nl < strEnd && buf[off + nl] != 0) nl++;
                    off += nl + 1;
                    nl = 0;
                    while (off + nl < strEnd && buf[off + nl] != 0) nl++;
                    off += nl + 1;
                    guard++;
                }
                off = strEnd;
            }
            matMeshState[i] = meshState;
        }

        // 顶点区：每顶点 28 字节，xyz @+8/+12/+16（raw int）
        int[] verts = new int[nVertex * 3];
        for (int i = 0; i < nVertex; i++) {
            verts[i * 3] = dv.getInt(off + 8);
            verts[i * 3 + 1] = dv.getInt(off + 12);
            verts[i * 3 + 2] = dv.getInt(off + 16);
            off += VERTEX_RECORD;
        }

        // 面区：每面 28 字节，3 索引 @+8/+10/+12 uint16，材质 @+14 uint16
        int[] triIdx = new int[nFace * 3];
        int[] faceMat = new int[nFace];
        for (int i = 0; i < nFace; i++) {
            triIdx[i * 3] = dv.getShort(off + 8) & 0xffff;
            triIdx[i * 3 + 1] = dv.getShort(off + 10) & 0xffff;
            triIdx[i * 3 + 2] = dv.getShort(off + 12) & 0xffff;
            faceMat[i] = dv.getShort(off + 14) & 0xffff;
            off += FACE_RECORD;
        }

        return new SmdMapData(nVertex, nFace, verts, triIdx, faceMat, matMeshState);
    }
}
