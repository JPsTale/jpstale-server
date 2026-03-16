package org.jpstale.server.common.struct.item;

import lombok.Data;
import org.jpstale.server.common.enums.item.ItemId;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct RespecStone。
 */
@Data
public final class RespecStone {

    public static final int SIZE_OF = 20;

    /** 对应 C++ EItemID eItemID; size: 4 bytes。 */
    private ItemId itemId;
    /** 对应 C++ int iMinLevel; size: 4 bytes。 */
    private int minLevel;
    /** 对应 C++ int iMaxLevel; size: 4 bytes。 */
    private int maxLevel;
    /** 对应 C++ int iRequiredStones; size: 4 bytes。 */
    private int requiredStones;
    /** 对应 C++ int iPrice; size: 4 bytes。 */
    private int price;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        itemId = ItemId.fromValue(in.getInt());
        minLevel = in.getInt();
        maxLevel = in.getInt();
        requiredStones = in.getInt();
        price = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(itemId != null ? itemId.getValue() : 0);
        out.putInt(minLevel);
        out.putInt(maxLevel);
        out.putInt(requiredStones);
        out.putInt(price);
    }
}

