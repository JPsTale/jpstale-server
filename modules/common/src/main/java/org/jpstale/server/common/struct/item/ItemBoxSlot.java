package org.jpstale.server.common.struct.item;

import lombok.Data;
import org.jpstale.server.common.codec.GameConstants;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct ItemBoxSlot。
 * 布局：int iNumItems + ItemBoxItem saItemBox[MAX_ITEMSINITEMBOX]（MAX_ITEMSINITEMBOX = 100）。
 */
@Data
public final class ItemBoxSlot {

    /** 对应 C++ #define MAX_ITEMSINITEMBOX 100。 */
    public static final int MAX_ITEMS_IN_ITEM_BOX = GameConstants.MAX_ITEMSINITEMBOX;

    /** 结构体总字节数：4 + MAX_ITEMSINITEMBOX * ItemBoxItem.SIZE_OF。 */
    public static final int SIZE_OF = 4 + MAX_ITEMS_IN_ITEM_BOX * ItemBoxItem.SIZE_OF;

    /** 对应 C++ int iNumItems; size: 4 bytes。 */
    private int numItems;
    /** 对应 C++ ItemBoxItem saItemBox[MAX_ITEMSINITEMBOX]; */
    private final ItemBoxItem[] items = new ItemBoxItem[MAX_ITEMS_IN_ITEM_BOX];

    public ItemBoxSlot() {
        for (int i = 0; i < items.length; i++) {
            items[i] = new ItemBoxItem();
        }
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        numItems = in.getInt();
        for (int i = 0; i < items.length; i++) {
            items[i].readFrom(in);
        }
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(numItems);
        for (ItemBoxItem item : items) {
            item.writeTo(out);
        }
    }
}

