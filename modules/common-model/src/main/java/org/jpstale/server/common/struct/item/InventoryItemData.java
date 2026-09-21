package org.jpstale.server.common.struct.item;

import lombok.Data;
import org.jpstale.server.common.struct.Rectangle2D;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct InventoryItemData。
 * 布局：int iPosition + Rectangle2D sBox + int iItemIndex + int iWeaponClass，总长 28 字节。
 */
@Data
public final class InventoryItemData {

    public static final int SIZE_OF = 28;

    /** 对应 C++ int iPosition; size: 4 bytes。 */
    private int position;
    /** 对应 C++ Rectangle2D sBox; size: 16 bytes。 */
    private Rectangle2D box;
    /** 对应 C++ int iItemIndex; size: 4 bytes。 */
    private int itemIndex;
    /** 对应 C++ int iWeaponClass; size: 4 bytes。 */
    private int weaponClass;

    public InventoryItemData() {
        box = new Rectangle2D();
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        position = in.getInt();
        box.readFrom(in);
        itemIndex = in.getInt();
        weaponClass = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(position);
        box.writeTo(out);
        out.putInt(itemIndex);
        out.putInt(weaponClass);
    }
}

