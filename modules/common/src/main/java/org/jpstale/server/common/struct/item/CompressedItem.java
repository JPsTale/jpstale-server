package org.jpstale.server.common.struct.item;

import lombok.Data;
import org.jpstale.server.common.struct.ItemID;

import java.nio.ByteBuffer;

import static org.jpstale.server.common.struct.packets.Packet.readCString;
import static org.jpstale.server.common.struct.packets.Packet.writeCString;

/**
 * 对应 C++ struct CompressedItem。
 * 布局：ItemID sItemID + char szBaseName[32] + char szInventoryName[16] +
 *       int iWidth + int iHeight + char szDropFolder[16] + UINT iClass +
 *       char szDropName[16] + UINT iModelPosition + UINT iSound + UINT iWeaponClass。
 * Total size: 108 bytes。
 */
@Data
public final class CompressedItem {

    public static final int SIZE_OF = 108;

    /** 对应 C++ ItemID sItemID; size: 4 bytes。 */
    private ItemID itemId;
    /** 对应 C++ char szBaseName[32]; size: 32 bytes。 */
    private String baseName;
    /** 对应 C++ char szInventoryName[16]; size: 16 bytes。 */
    private String inventoryName;
    /** 对应 C++ int iWidth; size: 4 bytes。 */
    private int width;
    /** 对应 C++ int iHeight; size: 4 bytes。 */
    private int height;
    /** 对应 C++ char szDropFolder[16]; size: 16 bytes。 */
    private String dropFolder;
    /** 对应 C++ UINT iClass; size: 4 bytes。 */
    private int itemClass;
    /** 对应 C++ char szDropName[16]; size: 16 bytes。 */
    private String dropName;
    /** 对应 C++ UINT iModelPosition; size: 4 bytes。 */
    private int modelPosition;
    /** 对应 C++ UINT iSound; size: 4 bytes。 */
    private int sound;
    /** 对应 C++ UINT iWeaponClass; size: 4 bytes。 */
    private int weaponClass;

    public CompressedItem() {
        itemId = new ItemID();
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        itemId.readFrom(in);
        baseName = readCString(in, 32);
        inventoryName = readCString(in, 16);
        width = in.getInt();
        height = in.getInt();
        dropFolder = readCString(in, 16);
        itemClass = in.getInt();
        dropName = readCString(in, 16);
        modelPosition = in.getInt();
        sound = in.getInt();
        weaponClass = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        itemId.writeTo(out);
        writeCString(out, baseName, 32);
        writeCString(out, inventoryName, 16);
        out.putInt(width);
        out.putInt(height);
        writeCString(out, dropFolder, 16);
        out.putInt(itemClass);
        writeCString(out, dropName, 16);
        out.putInt(modelPosition);
        out.putInt(sound);
        out.putInt(weaponClass);
    }
}

