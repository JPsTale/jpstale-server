package org.jpstale.server.common.struct.character;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct QuestCharacterSave。
 * 布局：WORD waQuestID[2] + DWORD dwaData[7]，总长 32 字节。
 */
@Data
public class QuestCharacterSave {

    /** 结构体总字节数。 */
    public static final int SIZE_OF = 32;

    /** 对应 C++ WORD waQuestID[2]，size: 4 bytes。 */
    private final short[] questId = new short[2];
    /** 对应 C++ DWORD dwaData[7]，size: 28 bytes。 */
    private final int[] data = new int[7];

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        for (int i = 0; i < questId.length; i++) {
            questId[i] = in.getShort();
        }
        for (int i = 0; i < data.length; i++) {
            data[i] = in.getInt();
        }
    }

    public void writeTo(ByteBuffer out) {
        for (short v : questId) {
            out.putShort(v);
        }
        for (int v : data) {
            out.putInt(v);
        }
    }
}
