package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

import static org.jpstale.server.common.struct.packets.Packet.readCString;
import static org.jpstale.server.common.struct.packets.Packet.writeCString;

/**
 * 对应 C++ struct ItemLogBox。
 */
@Data
public final class ItemLogBox {

    /** 结构体总字节数：int iSize + int iID + int iActionCode + SPlayer(68) + SItem(16) = 96 bytes。 */
    public static final int SIZE_OF = 96;

    /** 对应 C++ int iSize; size: 4 bytes。 */
    private int size;
    /** 对应 C++ int iID; size: 4 bytes。 */
    private int id;
    /** 对应 C++ int iActionCode; size: 4 bytes。 */
    private int actionCode;
    /** 对应 C++ SPlayer sPlayer; size: 68 bytes。 */
    private Player player;
    /** 对应 C++ SItem sItem; size: 16 bytes。 */
    private ItemEntry item;

    public ItemLogBox() {
        player = new Player();
        item = new ItemEntry();
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        size = in.getInt();
        id = in.getInt();
        actionCode = in.getInt();
        player.readFrom(in);
        item.readFrom(in);
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(size);
        out.putInt(id);
        out.putInt(actionCode);
        player.writeTo(out);
        item.writeTo(out);
    }

    /**
     * 对应 C++ struct SPlayer { char szAccountName[32]; char szCharacterName[32]; IN_ADDR iIP; }。
     */
    @Data
    public static final class Player {
        public static final int SIZE_OF = 68;

        /** 对应 C++ char szAccountName[32]; size: 32 bytes。 */
        private String accountName;
        /** 对应 C++ char szCharacterName[32]; size: 32 bytes。 */
        private String characterName;
        /** 对应 C++ IN_ADDR iIP; size: 4 bytes (IPv4)。 */
        private int ip;

        public void readFrom(ByteBuffer in) {
            accountName = readCString(in, 32);
            characterName = readCString(in, 32);
            ip = in.getInt();
        }

        public void writeTo(ByteBuffer out) {
            writeCString(out, accountName, 32);
            writeCString(out, characterName, 32);
            out.putInt(ip);
        }
    }

    /**
     * 对应 C++ struct SItem { int iItemID; int iCount; int iChecksum1; int iChecksum2; }。
     */
    @Data
    public static final class ItemEntry {
        public static final int SIZE_OF = 16;

        private int itemId;
        private int count;
        private int checksum1;
        private int checksum2;

        public void readFrom(ByteBuffer in) {
            itemId = in.getInt();
            count = in.getInt();
            checksum1 = in.getInt();
            checksum2 = in.getInt();
        }

        public void writeTo(ByteBuffer out) {
            out.putInt(itemId);
            out.putInt(count);
            out.putInt(checksum1);
            out.putInt(checksum2);
        }
    }
}

