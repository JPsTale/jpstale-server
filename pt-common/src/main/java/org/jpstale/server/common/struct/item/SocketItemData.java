package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct SocketItemData。
 * 布局：BOOL bOpenNPC + ItemData sItemData + ItemData sRune[5] + ItemData sStone
 *      + int iSocketWorkType + char cPadding[32] + int iItemCounter。
 */
@Data
public final class SocketItemData {

    public static final int RUNE_COUNT = 5;

    /** 结构体总字节数：4 + 7 * ItemData.SIZE_OF + 4 + 32 + 4 = 8472 bytes。 */
    public static final int SIZE_OF = 4 + 7 * ItemData.SIZE_OF + 4 + 32 + 4;

    /** 对应 C++ BOOL bOpenNPC; size: 4 bytes。 */
    private boolean openNpc;
    /** 对应 C++ ItemData sItemData; size: ItemData.SIZE_OF bytes。 */
    private ItemData itemData;
    /** 对应 C++ ItemData sRune[5]; size: 5 * ItemData.SIZE_OF bytes。 */
    private final ItemData[] rune = new ItemData[RUNE_COUNT];
    /** 对应 C++ ItemData sStone; size: ItemData.SIZE_OF bytes。 */
    private ItemData stone;
    /** 对应 C++ int iSocketWorkType; size: 4 bytes。 */
    private int socketWorkType;
    /** 对应 C++ char cPadding[32]; size: 32 bytes。 */
    private final byte[] padding = new byte[32];
    /** 对应 C++ int iItemCounter; size: 4 bytes。 */
    private int itemCounter;

    public SocketItemData() {
        itemData = new ItemData();
        stone = new ItemData();
        for (int i = 0; i < rune.length; i++) {
            rune[i] = new ItemData();
        }
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        openNpc = in.getInt() != 0;
        itemData.readFrom(in);
        for (int i = 0; i < rune.length; i++) {
            rune[i].readFrom(in);
        }
        stone.readFrom(in);
        socketWorkType = in.getInt();
        in.get(padding);
        itemCounter = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(openNpc ? 1 : 0);
        itemData.writeTo(out);
        for (ItemData r : rune) {
            r.writeTo(out);
        }
        stone.writeTo(out);
        out.putInt(socketWorkType);
        out.put(padding);
        out.putInt(itemCounter);
    }
}

