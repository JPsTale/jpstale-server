package org.jpstale.server.common.struct.item;

import lombok.Data;

import java.nio.ByteBuffer;

/**
 * 对应 C++ struct DefinitionItem。
 * 布局：Item sItem (0x0) + 各 Min/Max short/float/int 及数组 + DWORD JobBitCodeRandom[0xC] +
 * JobBitCodeRandomCount + sGenDay[2] + DefCompressData[0x214]（指针数组）+ DefCompressDataLen。
 * 注释中的偏移（如 0x214）为 C++ 中该字段偏移，供对照。
 */
@Data
public final class DefinitionItem {

    private static final int DEF_COMPRESS_DATA_COUNT = 0x214; // 532

    /** 结构体总字节数（0xDF8 = 3576）：Item.SIZE_OF(0x4C4) + 后续 Min/Max 与数组。 */
    public static final int SIZE_OF = 0xDF8;

    /** 对应 C++ struct Item sItem; // 0x0，size: Item.SIZE_OF。 */
    private Item item;
    /** 对应 C++ short IntegrityMin; // 0x214，size: 2 bytes。 */
    private short integrityMin;
    /** 对应 C++ short IntegrityMax; // 0x216，size: 2 bytes。 */
    private short integrityMax;
    /** 对应 C++ short OrganicMin; // 0x218，size: 2 bytes。 */
    private short organicMin;
    /** 对应 C++ short OrganicMax; // 0x21A，size: 2 bytes。 */
    private short organicMax;
    /** 对应 C++ short UnknownResistanseMin; // 0x21C，size: 2 bytes。 */
    private short unknownResistanceMin;
    /** 对应 C++ short UnknownResistanseMax; // 0x21E，size: 2 bytes。 */
    private short unknownResistanceMax;
    /** 对应 C++ short FireMin; // 0x220，size: 2 bytes。 */
    private short fireMin;
    /** 对应 C++ short FireMax; // 0x222，size: 2 bytes。 */
    private short fireMax;
    /** 对应 C++ short FrostMin; // 0x224，size: 2 bytes。 */
    private short frostMin;
    /** 对应 C++ short FrostMax; // 0x226，size: 2 bytes。 */
    private short frostMax;
    /** 对应 C++ short LightningMin; // 0x228，size: 2 bytes。 */
    private short lightningMin;
    /** 对应 C++ short LightningMax; // 0x22A，size: 2 bytes。 */
    private short lightningMax;
    /** 对应 C++ short PoisonMin; // 0x22C，size: 2 bytes。 */
    private short poisonMin;
    /** 对应 C++ short PoisonMax; // 0x22E，size: 2 bytes。 */
    private short poisonMax;
    /** 对应 C++ short sUnknownResistance[4]; // 0x230，size: 8 bytes。 */
    private final short[] unknownResistance = new short[4];
    /** 对应 C++ short AttackPower1Min; // 0x238 (Min of Min value)，size: 2 bytes。 */
    private short attackPower1Min;
    /** 对应 C++ short AttackPower2Min; // 0x23A (Min of Max value)，size: 2 bytes。 */
    private short attackPower2Min;
    /** 对应 C++ short AttackPower1Max; // 0x23C (Max of Min value)，size: 2 bytes。 */
    private short attackPower1Max;
    /** 对应 C++ short AttackPower2Max; // 0x23E (Max of Max value)，size: 2 bytes。 */
    private short attackPower2Max;
    /** 对应 C++ short AttackRatingMin; // 0x240，size: 2 bytes。 */
    private short attackRatingMin;
    /** 对应 C++ short AttackRatingMax; // 0x242，size: 2 bytes。 */
    private short attackRatingMax;
    /** 对应 C++ short DefenseMin; // 0x244，size: 2 bytes。 */
    private short defenseMin;
    /** 对应 C++ short DefenseMax; // 0x246，size: 2 bytes。 */
    private short defenseMax;
    /** 对应 C++ float BlockRatingMin; // 0x248，size: 4 bytes。 */
    private float blockRatingMin;
    /** 对应 C++ float BlockRatingMax; // 0x24C，size: 4 bytes。 */
    private float blockRatingMax;
    /** 对应 C++ float AbsorbMin; // 0x250，size: 4 bytes。 */
    private float absorbMin;
    /** 对应 C++ float AbsorbMax; // 0x254，size: 4 bytes。 */
    private float absorbMax;
    /** 对应 C++ float RunSpeedMin; // 0x258，size: 4 bytes。 */
    private float runSpeedMin;
    /** 对应 C++ float RunSpeedMax; // 0x25C，size: 4 bytes。 */
    private float runSpeedMax;
    /** 对应 C++ int AddHPMin; // 0x260，size: 4 bytes。 */
    private int addHpMin;
    /** 对应 C++ int AddHPMax; // 0x264，size: 4 bytes。 */
    private int addHpMax;
    /** 对应 C++ int AddMPMin; // 0x268，size: 4 bytes。 */
    private int addMpMin;
    /** 对应 C++ int AddMPMax; // 0x26C，size: 4 bytes。 */
    private int addMpMax;
    /** 对应 C++ int AddSTMMin; // 0x270，size: 4 bytes。 */
    private int addStmMin;
    /** 对应 C++ int AddSTMMax; // 0x274，size: 4 bytes。 */
    private int addStmMax;
    /** 对应 C++ float MPRegenMin; // 0x278，size: 4 bytes。 */
    private float mpRegenMin;
    /** 对应 C++ float MPRegenMax; // 0x27C，size: 4 bytes。 */
    private float mpRegenMax;
    /** 对应 C++ float HPRegenMin; // 0x280，size: 4 bytes。 */
    private float hpRegenMin;
    /** 对应 C++ float HPRegenMax; // 0x284，size: 4 bytes。 */
    private float hpRegenMax;
    /** 对应 C++ float STMRegenMin; // 0x288，size: 4 bytes。 */
    private float stmRegenMin;
    /** 对应 C++ float STMRegenMax; // 0x28C，size: 4 bytes。 */
    private float stmRegenMax;
    /** 对应 C++ short AddSpecDefenseMin; // 0x290，size: 2 bytes。 */
    private short addSpecDefenseMin;
    /** 对应 C++ short AddSpecDefenseMax; // 0x292，size: 2 bytes。 */
    private short addSpecDefenseMax;
    /** 对应 C++ float AddSpecAbsorbMin; // 0x294，size: 4 bytes。 */
    private float addSpecAbsorbMin;
    /** 对应 C++ float AddSpecAbsorbMax; // 0x298，size: 4 bytes。 */
    private float addSpecAbsorbMax;
    /** 对应 C++ float AddSpecRunSpeedMin; // 0x29C，size: 4 bytes。 */
    private float addSpecRunSpeedMin;
    /** 对应 C++ float AddSpecRunSpeedMax; // 0x2A0，size: 4 bytes。 */
    private float addSpecRunSpeedMax;
    /** 对应 C++ float SpecialMagicMastery[2]; // 0x2A4，size: 8 bytes。 */
    private final float[] specialMagicMastery = new float[2];
    /** 对应 C++ float AddSpecMPRegenMin; // 0x2AC，size: 4 bytes。 */
    private float addSpecMpRegenMin;
    /** 对应 C++ float AddSpecMPRegenMax; // 0x2B0，size: 4 bytes。 */
    private float addSpecMpRegenMax;
    /** 对应 C++ int SpecAttackRatingMin; // 0x2B4，size: 4 bytes。 */
    private int specAttackRatingMin;
    /** 对应 C++ int SpecAttackRatingMax; // 0x2B8，size: 4 bytes。 */
    private int specAttackRatingMax;
    /** 对应 C++ DWORD JobBitCodeRandom[0xC]; // 0x2BC，size: 48 bytes。 */
    private final int[] jobBitCodeRandom = new int[0xC];
    /** 对应 C++ int JobBitCodeRandomCount; // 0x2EC，size: 4 bytes。 */
    private int jobBitCodeRandomCount;
    /** 对应 C++ short sGenDay[2]; // 0x2F0，size: 4 bytes。 */
    private final short[] genDay = new short[2];
    /** 对应 C++ unsigned char* DefCompressData[0x214]; // 0x2F4，按 DWORD 存储 532 个指针，size: 2128 bytes。 */
    private final int[] defCompressData = new int[DEF_COMPRESS_DATA_COUNT];
    /** 对应 C++ int DefCompressDataLen; // 0xB44，size: 4 bytes。 */
    private int defCompressDataLen;

    public DefinitionItem() {
        item = new Item();
    }

    public int sizeOf() {
        return SIZE_OF;
    }

    public void readFrom(ByteBuffer in) {
        item.readFrom(in);
        integrityMin = in.getShort();
        integrityMax = in.getShort();
        organicMin = in.getShort();
        organicMax = in.getShort();
        unknownResistanceMin = in.getShort();
        unknownResistanceMax = in.getShort();
        fireMin = in.getShort();
        fireMax = in.getShort();
        frostMin = in.getShort();
        frostMax = in.getShort();
        lightningMin = in.getShort();
        lightningMax = in.getShort();
        poisonMin = in.getShort();
        poisonMax = in.getShort();
        for (int i = 0; i < unknownResistance.length; i++) {
            unknownResistance[i] = in.getShort();
        }
        attackPower1Min = in.getShort();
        attackPower2Min = in.getShort();
        attackPower1Max = in.getShort();
        attackPower2Max = in.getShort();
        attackRatingMin = in.getShort();
        attackRatingMax = in.getShort();
        defenseMin = in.getShort();
        defenseMax = in.getShort();
        blockRatingMin = in.getFloat();
        blockRatingMax = in.getFloat();
        absorbMin = in.getFloat();
        absorbMax = in.getFloat();
        runSpeedMin = in.getFloat();
        runSpeedMax = in.getFloat();
        addHpMin = in.getInt();
        addHpMax = in.getInt();
        addMpMin = in.getInt();
        addMpMax = in.getInt();
        addStmMin = in.getInt();
        addStmMax = in.getInt();
        mpRegenMin = in.getFloat();
        mpRegenMax = in.getFloat();
        hpRegenMin = in.getFloat();
        hpRegenMax = in.getFloat();
        stmRegenMin = in.getFloat();
        stmRegenMax = in.getFloat();
        addSpecDefenseMin = in.getShort();
        addSpecDefenseMax = in.getShort();
        addSpecAbsorbMin = in.getFloat();
        addSpecAbsorbMax = in.getFloat();
        addSpecRunSpeedMin = in.getFloat();
        addSpecRunSpeedMax = in.getFloat();
        for (int i = 0; i < specialMagicMastery.length; i++) {
            specialMagicMastery[i] = in.getFloat();
        }
        addSpecMpRegenMin = in.getFloat();
        addSpecMpRegenMax = in.getFloat();
        specAttackRatingMin = in.getInt();
        specAttackRatingMax = in.getInt();
        for (int i = 0; i < jobBitCodeRandom.length; i++) {
            jobBitCodeRandom[i] = in.getInt();
        }
        jobBitCodeRandomCount = in.getInt();
        for (int i = 0; i < genDay.length; i++) {
            genDay[i] = in.getShort();
        }
        for (int i = 0; i < defCompressData.length; i++) {
            defCompressData[i] = in.getInt();
        }
        defCompressDataLen = in.getInt();
    }

    public void writeTo(ByteBuffer out) {
        item.writeTo(out);
        out.putShort(integrityMin);
        out.putShort(integrityMax);
        out.putShort(organicMin);
        out.putShort(organicMax);
        out.putShort(unknownResistanceMin);
        out.putShort(unknownResistanceMax);
        out.putShort(fireMin);
        out.putShort(fireMax);
        out.putShort(frostMin);
        out.putShort(frostMax);
        out.putShort(lightningMin);
        out.putShort(lightningMax);
        out.putShort(poisonMin);
        out.putShort(poisonMax);
        for (short v : unknownResistance) {
            out.putShort(v);
        }
        out.putShort(attackPower1Min);
        out.putShort(attackPower2Min);
        out.putShort(attackPower1Max);
        out.putShort(attackPower2Max);
        out.putShort(attackRatingMin);
        out.putShort(attackRatingMax);
        out.putShort(defenseMin);
        out.putShort(defenseMax);
        out.putFloat(blockRatingMin);
        out.putFloat(blockRatingMax);
        out.putFloat(absorbMin);
        out.putFloat(absorbMax);
        out.putFloat(runSpeedMin);
        out.putFloat(runSpeedMax);
        out.putInt(addHpMin);
        out.putInt(addHpMax);
        out.putInt(addMpMin);
        out.putInt(addMpMax);
        out.putInt(addStmMin);
        out.putInt(addStmMax);
        out.putFloat(mpRegenMin);
        out.putFloat(mpRegenMax);
        out.putFloat(hpRegenMin);
        out.putFloat(hpRegenMax);
        out.putFloat(stmRegenMin);
        out.putFloat(stmRegenMax);
        out.putShort(addSpecDefenseMin);
        out.putShort(addSpecDefenseMax);
        out.putFloat(addSpecAbsorbMin);
        out.putFloat(addSpecAbsorbMax);
        out.putFloat(addSpecRunSpeedMin);
        out.putFloat(addSpecRunSpeedMax);
        for (float v : specialMagicMastery) {
            out.putFloat(v);
        }
        out.putFloat(addSpecMpRegenMin);
        out.putFloat(addSpecMpRegenMax);
        out.putInt(specAttackRatingMin);
        out.putInt(specAttackRatingMax);
        for (int v : jobBitCodeRandom) {
            out.putInt(v);
        }
        out.putInt(jobBitCodeRandomCount);
        for (short v : genDay) {
            out.putShort(v);
        }
        for (int v : defCompressData) {
            out.putInt(v);
        }
        out.putInt(defCompressDataLen);
    }
}
