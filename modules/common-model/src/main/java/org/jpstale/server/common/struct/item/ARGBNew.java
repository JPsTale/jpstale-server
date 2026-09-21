package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct ARGBNew { int red; int green; int blue; int alpha; }。
 */
@Data
public final class ARGBNew {

    public static final int SIZE_OF = 16;

    private int red;
    private int green;
    private int blue;
    private int alpha;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        red = in.getInt();
        green = in.getInt();
        blue = in.getInt();
        alpha = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(red);
        out.putInt(green);
        out.putInt(blue);
        out.putInt(alpha);
    }
}

