package org.jpstale.server.common.struct.character;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct LastQuestSave（LAST_QUEST_MAX = 32）。
 * 布局：WORD waQuestID[LAST_QUEST_MAX] + int iCount，总长 68 字节。
 */
@Data
public class LastQuestSave {

    /** 对应 C++ #define LAST_QUEST_MAX 32。 */
    public static final int LAST_QUEST_MAX = 32;

    /** 结构体总字节数。 */
    public static final int SIZE_OF = 68;

    /** 对应 C++ WORD waQuestID[LAST_QUEST_MAX]，size: 64 bytes。 */
    private final short[] questId = new short[LAST_QUEST_MAX];
    /** 对应 C++ int iCount，size: 4 bytes。 */
    private int count;

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        for (int i = 0; i < questId.length; i++) {
            questId[i] = in.getShort();
        }
        count = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        for (short v : questId) {
            out.putShort(v);
        }
        out.putInt(count);
    }
}
