package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

import static org.jpstale.server.common.struct.packets.Packet.readCString;
import static org.jpstale.server.common.struct.packets.Packet.writeCString;

/**
 * 对应 C++ struct PlayerTitle。
 * 布局：unsigned short sTitleID + short sRarity + char szTitle[20]，总长 24 字节。
 */
@Data
public final class PlayerTitle {

    public static final int SIZE_OF = 24;

    /** 对应 C++ unsigned short sTitleID; size: 2 bytes。 */
    private short titleId;
    /** 对应 C++ short sRarity; size: 2 bytes。 */
    private short rarity;
    /** 对应 C++ char szTitle[20]; size: 20 bytes。 */
    private String title;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        titleId = in.getShort();
        rarity = in.getShort();
        title = readCString(in, 20);
    }

    public void writeTo(ByteBuffer out) {
        out.putShort(titleId);
        out.putShort(rarity);
        writeCString(out, title, 20);
    }
}

