package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct ItemBoxItem。
 */
@Data
public final class ItemBoxItem {

    /** 结构体总字节数：int + BOOL + 3 * int + 2 * BOOL = 28 bytes。 */
    public static final int SIZE_OF = 28;

    /** 对应 C++ int iID; size: 4 bytes。 */
    private int id;
    /** 对应 C++ BOOL bValid; size: 4 bytes。 */
    private boolean valid;
    /** 对应 C++ int iItemID; size: 4 bytes。 */
    private int itemId;
    /** 对应 C++ int iCount; size: 4 bytes。 */
    private int count;
    /** 对应 C++ int iSpecialization; size: 4 bytes。 */
    private int specialization;
    /** 对应 C++ BOOL bCoinShop; size: 4 bytes。 */
    private boolean coinShop;
    /** 对应 C++ BOOL bHasItem; size: 4 bytes。 */
    private boolean hasItem;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        id = in.getInt();
        valid = in.getInt() != 0;
        itemId = in.getInt();
        count = in.getInt();
        specialization = in.getInt();
        coinShop = in.getInt() != 0;
        hasItem = in.getInt() != 0;
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(id);
        out.putInt(valid ? 1 : 0);
        out.putInt(itemId);
        out.putInt(count);
        out.putInt(specialization);
        out.putInt(coinShop ? 1 : 0);
        out.putInt(hasItem ? 1 : 0);
    }
}

