package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct RGB { int red; int green; int blue; }。
 */
@Data
public final class RGB {

    public static final int SIZE_OF = 12;

    private int red;
    private int green;
    private int blue;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        red = in.getInt();
        green = in.getInt();
        blue = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(red);
        out.putInt(green);
        out.putInt(blue);
    }
}

