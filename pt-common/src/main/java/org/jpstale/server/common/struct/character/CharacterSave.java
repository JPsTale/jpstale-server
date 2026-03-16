package org.jpstale.server.common.struct.character;

import lombok.Data;
import org.jpstale.server.common.enums.map.MapId;
import org.jpstale.server.common.struct.skill.RecordSkill;

import java.nio.ByteBuffer;

import static org.jpstale.server.common.struct.packets.Packet.readCString;
import static org.jpstale.server.common.struct.packets.Packet.writeCString;

/**
 * 对应 C++ sGAME_SAVE_INFO / struct CharacterSave，总长 512 字节。
 */
@Data
public class CharacterSave {

    public static final int SIZE_OF = 512;

    /** 对应 C++ DWORD dwHeader，size: 4 bytes。 */
    private int header;
    /** 对应 C++ EMapID iMapID，size: 4 bytes。 */
    private MapId mapId;
    /** 对应 C++ int iCameraMode，size: 4 bytes。 */
    private int cameraMode;
    /** 对应 C++ int iCameraPositionX，size: 4 bytes。 */
    private int cameraPositionX;
    /** 对应 C++ int iCameraPositionZ，size: 4 bytes。 */
    private int cameraPositionZ;
    /** 对应 C++ int iLastGold，size: 4 bytes。 */
    private int lastGold;
    /** 对应 C++ DWORD dwChecksum，size: 4 bytes。 */
    private int checksum;
    /** 对应 C++ RecordSkill sSkillInfo，size: 84 bytes。 */
    private RecordSkill skillInfo = new RecordSkill();
    /** 对应 C++ int iSaveTime，size: 4 bytes。 */
    private int saveTime;
    /** 对应 C++ int iPadding69[3]，size: 12 bytes。 */
    private final int[] padding69 = new int[3];
    /** 对应 C++ short sPotionUpdate[2]（may not be at correct position），size: 4 bytes。 */
    private final short[] potionUpdate = new short[2];
    /** 对应 C++ short sPotionCount[3][4]（may not be at correct position），size: 24 bytes。 */
    private final short[][] potionCount = new short[3][4];
    /** 对应 C++ WORD wForceOrbUsing[2] // 0x9C，size: 4 bytes。 */
    private final short[] forceOrbUsing = new short[2];
    /** 对应 C++ DWORD iQuestLog // 0xA0，size: 4 bytes。 */
    private int questLog;
    /** 对应 C++ int iShortKeyDefaultSkill，size: 4 bytes。 */
    private int shortKeyDefaultSkill;
    /** 对应 C++ int iBlessCastleTax，size: 4 bytes。 */
    private int blessCastleTax;
    /** 对应 C++ DWORD iBlessCastleClanID，size: 4 bytes。 */
    private int blessCastleClanId;
    /** 对应 C++ int iPadding72[13]，size: 52 bytes。 */
    private final int[] padding72 = new int[13];
    /** 对应 C++ int iTotalSubExp，size: 4 bytes。 */
    private int totalSubExp;
    /** 对应 C++ int iTotalGold，size: 4 bytes。 */
    private int totalGold;
    /** 对应 C++ int iTotalExp，size: 4 bytes。 */
    private int totalExp;
    /** 对应 C++ char szAccountName[32]，size: 32 bytes。 */
    private String accountName;
    /** 对应 C++ QuestCharacterSave sQuestInfo，size: 32 bytes。 */
    private QuestCharacterSave questInfo = new QuestCharacterSave();
    /** 对应 C++ LastQuestSave sLastQuestInfo，size: 68 bytes。 */
    private LastQuestSave lastQuestInfo = new LastQuestSave();
    /** 对应 C++ int iPadding08[35]，size: 140 bytes。 */
    private final int[] padding08 = new int[35];

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        header = in.getInt();
        mapId = MapId.fromValue(in.getInt());
        cameraMode = in.getInt();
        cameraPositionX = in.getInt();
        cameraPositionZ = in.getInt();
        lastGold = in.getInt();
        checksum = in.getInt();

        skillInfo.readFrom(in);
        saveTime = in.getInt();

        for (int i = 0; i < padding69.length; i++) {
            padding69[i] = in.getInt();
        }
        for (int i = 0; i < potionUpdate.length; i++) {
            potionUpdate[i] = in.getShort();
        }
        for (int i = 0; i < potionCount.length; i++) {
            for (int j = 0; j < potionCount[i].length; j++) {
                potionCount[i][j] = in.getShort();
            }
        }
        for (int i = 0; i < forceOrbUsing.length; i++) {
            forceOrbUsing[i] = in.getShort();
        }
        questLog = in.getInt();
        shortKeyDefaultSkill = in.getInt();
        blessCastleTax = in.getInt();
        blessCastleClanId = in.getInt();

        for (int i = 0; i < padding72.length; i++) {
            padding72[i] = in.getInt();
        }
        totalSubExp = in.getInt();
        totalGold = in.getInt();
        totalExp = in.getInt();
        accountName = readCString(in, 32);

        questInfo.readFrom(in);
        lastQuestInfo.readFrom(in);

        for (int i = 0; i < padding08.length; i++) {
            padding08[i] = in.getInt();
        }
    }

    public void writeTo(ByteBuffer out) {
        out.putInt(header);
        out.putInt(mapId != null ? mapId.getValue() : -1);
        out.putInt(cameraMode);
        out.putInt(cameraPositionX);
        out.putInt(cameraPositionZ);
        out.putInt(lastGold);
        out.putInt(checksum);

        skillInfo.writeTo(out);
        out.putInt(saveTime);

        for (int v : padding69) {
            out.putInt(v);
        }
        for (short v : potionUpdate) {
            out.putShort(v);
        }
        for (short[] row : potionCount) {
            for (short v : row) {
                out.putShort(v);
            }
        }
        for (short v : forceOrbUsing) {
            out.putShort(v);
        }
        out.putInt(questLog);
        out.putInt(shortKeyDefaultSkill);
        out.putInt(blessCastleTax);
        out.putInt(blessCastleClanId);

        for (int v : padding72) {
            out.putInt(v);
        }
        out.putInt(totalSubExp);
        out.putInt(totalGold);
        out.putInt(totalExp);
        writeCString(out, accountName, 32);

        questInfo.writeTo(out);
        lastQuestInfo.writeTo(out);

        for (int v : padding08) {
            out.putInt(v);
        }
    }
}
