package org.jpstale.server.common.struct.item;

import lombok.Data;
import org.jpstale.server.common.enums.item.ItemId;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct SellItemShopData。
 * 布局：EItemID eItemID + DWORD dwHead + DWORD dwChecksum + int iPrice，总长 16 字节。
 */
@Data
public final class SellItemShopData {

    public static final int SIZE_OF = 16;

    /** 对应 C++ EItemID eItemID; size: 4 bytes。 */
    private ItemId itemId;
    /** 对应 C++ DWORD dwHead; size: 4 bytes。 */
    private int head;
    /** 对应 C++ DWORD dwChecksum; size: 4 bytes。 */
    private int checksum;
    /** 对应 C++ int iPrice; size: 4 bytes。 */
    private int price;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        itemId = ItemId.fromValue(in.getInt());
        head = in.getInt();
        checksum = in.getInt();
        price = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(itemId != null ? itemId.getValue() : 0);
        out.putInt(head);
        out.putInt(checksum);
        out.putInt(price);
    }
}

