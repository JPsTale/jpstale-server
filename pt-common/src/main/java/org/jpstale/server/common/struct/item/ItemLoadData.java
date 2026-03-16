package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct ItemLoadData。
 * 布局：BOOL bState + DWORD dwSerial + Item sItem。
 */
@Data
public final class ItemLoadData {

    /** 结构体总字节数：4 + 4 + Item.SIZE_OF。 */
    public static final int SIZE_OF = 8 + Item.SIZE_OF;

    /** 对应 C++ BOOL bState; size: 4 bytes。 */
    private boolean state;
    /** 对应 C++ DWORD dwSerial; size: 4 bytes。 */
    private int serial;
    /** 对应 C++ Item sItem; size: Item.SIZE_OF bytes。 */
    private Item item;

    public ItemLoadData() {
        item = new Item();
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        state = in.getInt() != 0;
        serial = in.getInt();
        item.readFrom(in);
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(state ? 1 : 0);
        out.putInt(serial);
        item.writeTo(out);
    }
}

