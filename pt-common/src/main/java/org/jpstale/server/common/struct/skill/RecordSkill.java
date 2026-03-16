package org.jpstale.server.common.struct.skill;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct RecordSkill。
 * 布局：BYTE baSkillPoints[20] + WORD waSkillMastery[20] + BYTE baShortKey[20] + WORD waSelectSkill[2]，总长 84 字节。
 */
@Data
public class RecordSkill {

    /** 结构体总字节数。 */
    public static final int SIZE_OF = 84;

    /** 对应 C++ BYTE baSkillPoints[20]，size: 20 bytes。 */
    private final byte[] skillPoints = new byte[20];
    /** 对应 C++ WORD waSkillMastery[20]，size: 40 bytes。 */
    private final short[] skillMastery = new short[20];
    /** 对应 C++ BYTE baShortKey[20]，size: 20 bytes。 */
    private final byte[] shortKey = new byte[20];
    /** 对应 C++ WORD waSelectSkill[2]，size: 4 bytes。 */
    private final short[] selectSkill = new short[2];

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        in.get(skillPoints);
        for (int i = 0; i < skillMastery.length; i++) {
            skillMastery[i] = in.getShort();
        }
        in.get(shortKey);
        for (int i = 0; i < selectSkill.length; i++) {
            selectSkill[i] = in.getShort();
        }
    }

    public void writeTo(ByteBuffer out) {
        out.put(skillPoints);
        for (short v : skillMastery) {
            out.putShort(v);
        }
        out.put(shortKey);
        for (short v : selectSkill) {
            out.putShort(v);
        }
    }
}
