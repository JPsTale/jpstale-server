package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct PriceItem。
 */
@Data
public final class PriceItem {

    public static final int SIZE_OF = 12;

    /** 对应 C++ int iRealPrice; size: 4 bytes。 */
    private int realPrice;
    /** 对应 C++ int iPrice; size: 4 bytes。 */
    private int price;
    /** 对应 C++ int iRepairPrice; size: 4 bytes。 */
    private int repairPrice;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        realPrice = in.getInt();
        price = in.getInt();
        repairPrice = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(realPrice);
        out.putInt(price);
        out.putInt(repairPrice);
    }
}

