package org.jpstale.server.common.struct;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ Rectangle2D：假定为 int x, int y, int width, int height，总长 16 字节。
 * 若后续确认 C++ 为 left/top/right/bottom，可在不改变总字节数的前提下调整语义。
 */
@Data
public final class Rectangle2D {

    public static final int SIZE_OF = 16;

    /** 对应 C++ Rectangle2D::x，size: 4 bytes。 */
    private int x;
    /** 对应 C++ Rectangle2D::y，size: 4 bytes。 */
    private int y;
    /** 对应 C++ Rectangle2D::width，size: 4 bytes。 */
    private int width;
    /** 对应 C++ Rectangle2D::height，size: 4 bytes。 */
    private int height;

    public void readFrom(ByteBuffer in) {
        x = in.getInt();
        y = in.getInt();
        width = in.getInt();
        height = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(x);
        out.putInt(y);
        out.putInt(width);
        out.putInt(height);
    }

    public int sizeOf() {
        return SIZE_OF;
    }
}

