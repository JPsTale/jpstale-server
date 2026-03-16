package org.jpstale.server.common.struct.packets;

import lombok.Data;
import org.jpstale.server.common.struct.character.CharacterData;
import org.jpstale.server.common.struct.character.CharacterSave;

import java.nio.ByteBuffer;

/**
 * 对应 packets.h 中 struct PacketCharacterRecordData : Packet。
 */

@Data
public class PacketCharacterRecordData extends Packet {

    /** 本包体字节数（不含包头）. */
    public static final int SIZE_OF = 111368;

    private String header;  // char szHeader[8]  size: 8 bytes
    private CharacterData characterData = new CharacterData();  // CharacterData sCharacterData  size: 464 bytes
    private CharacterSave characterSaveData = new CharacterSave();  // CharacterSave sCharacterSaveData  size: 512 bytes
    private DropItemData[] dropItemData = new DropItemData[64];  // DropItemData sDropItemData[64]  size: 768 bytes
    private int dropItemCount;  // int iDropItemCount  size: 4 bytes
    private int itemCount;  // int iItemCount  size: 4 bytes
    private int itemSubStart;  // int iItemSubStart  size: 4 bytes
    private int dataSize;  // int iDataSize  size: 4 bytes
    private final byte[] data = new byte[548*200];  // BYTE baData[0]  size: sizeof(RecordItem) * 200 => 548 * 200, 200 = RECORD_ITEM_MAX

    @Override
    public int sizeOf() {
        return super.sizeOf() + SIZE_OF;
    }

    @Override
    protected void readBody(ByteBuffer in) {
        header = readCString(in, 8);
        characterData.readFrom(in);
        characterSaveData.readFrom(in);
        for (int i = 0; i < dropItemData.length; i++) { if (dropItemData[i] == null) dropItemData[i] = new DropItemData(); dropItemData[i].readFrom(in); }
        dropItemCount = in.getInt();
        itemCount = in.getInt();
        itemSubStart = in.getInt();
        dataSize = in.getInt();
        in.get(data);
    }

    @Override
    protected void writeBody(ByteBuffer out) {
        writeCString(out, header, 8);
        characterData.writeTo(out);
        characterData.writeTo(out);
        for (int i = 0; i < dropItemData.length; i++) { if (dropItemData[i] != null) dropItemData[i].writeTo(out); }
        out.putInt(dropItemCount);
        out.putInt(itemCount);
        out.putInt(itemSubStart);
        out.putInt(dataSize);
        out.put(data);
    }
}
