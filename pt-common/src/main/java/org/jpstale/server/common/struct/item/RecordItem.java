package org.jpstale.server.common.struct.item;

import lombok.Data;
import org.jpstale.server.common.struct.Point2D;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct RecordItem。
 * 布局：int iItemCount + Point2D sItemPosition + int iItemPosition + Item sItem。
 * Total size: 4 + 8 + 4 + Item.SIZE_OF = 1236 bytes。
 */
@Data
public final class RecordItem {

    public static final int SIZE_OF = 4 + Point2D.SIZE_OF + 4 + Item.SIZE_OF;

    /** 对应 C++ int iItemCount; size: 4 bytes。 */
    private int itemCount;
    /** 对应 C++ Point2D sItemPosition; size: 8 bytes。 */
    private Point2D itemPosition;
    /** 对应 C++ int iItemPosition; size: 4 bytes。 */
    private int itemPositionIndex;
    /** 对应 C++ Item sItem; size: Item.SIZE_OF bytes。 */
    private Item item;

    public RecordItem() {
        itemPosition = new Point2D();
        item = new Item();
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        itemCount = in.getInt();
        itemPosition.readFrom(in);
        itemPositionIndex = in.getInt();
        item.readFrom(in);
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(itemCount);
        itemPosition.writeTo(out);
        out.putInt(itemPositionIndex);
        item.writeTo(out);
    }
}

