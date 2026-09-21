package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct DropSettingsData。
 * 布局：int iItemID (0x00) + int iPercent (0x04) + short sGoldMin (0x08) + short sGoldMax (0x0A)，总长 12 字节。
 */
@Data
public final class DropSettingsData {

    public static final int SIZE_OF = 12;

    /** 对应 C++ int iItemID; // 0x00，size: 4 bytes。 */
    private int itemId;
    /** 对应 C++ int iPercent; // 0x04，size: 4 bytes。 */
    private int percent;
    /** 对应 C++ short sGoldMin; // 0x08，size: 2 bytes。 */
    private short goldMin;
    /** 对应 C++ short sGoldMax; // 0x0A，size: 2 bytes。 */
    private short goldMax;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        itemId = in.getInt();
        percent = in.getInt();
        goldMin = in.getShort();
        goldMax = in.getShort();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(itemId);
        out.putInt(percent);
        out.putShort(goldMin);
        out.putShort(goldMax);
    }
}

