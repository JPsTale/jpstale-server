package org.jpstale.server.common.struct.character;

import lombok.Data;
import org.jpstale.server.common.enums.character.MonsterClass;
import org.jpstale.server.common.enums.character.MonsterEffectId;
import org.jpstale.server.common.enums.packets.CharacterClass;
import org.jpstale.server.common.enums.packets.CharacterRank;
import org.jpstale.server.common.enums.packets.CharacterType;
import org.jpstale.server.common.enums.packets.ClassFlag;
import org.jpstale.server.common.enums.packets.MonsterType;
import org.jpstale.server.common.struct.CurMax;
import org.jpstale.server.common.struct.MinMax;
import org.jpstale.server.common.struct.packets.Packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * shared/character.h
 *
 * @author yanmaoyuan
 */
@Data
public class CharacterData {
    /** 对应 C++ `char szName[32]`，size: 32 bytes. */
    public static final int SIZE_OF = 464; // 0x01D0 bytes

    /** 对应 C++ `char szName[32]`，size: 32 bytes. */
    private String name;

    /**
     union
     {
     struct
     {
     char		  szBodyModel[64];
     char		  szHeadModel[64];
     } Player;
     struct
     {
     char		  szBodyModel[64];
     char		  szHeadModel[60];
     int			  iMonsterID;
     } Monster;
     struct
     {
     char		  szBodyModel[64];
     char		  szHeadModel[60];
     int			  iNPCID;
     } NPC;
     struct
     {
     char		  szBodyModel[65];
     char		  szOwnerName[63];
     } Pet;
     };
     */
    /** 与 C++ 联合体共享的原始 128 字节数据。size: 128 bytes. */
    private final byte[] union = new byte[128];

    /** 对应 C++ `unsigned int iID; // 0xA0 - dwObjectSerial`，size: 4 bytes. */
    private int id;
    /** 对应 C++ `unsigned int iClanID; // 0xA4 - ClassClan`，size: 4 bytes. */
    private int clanId;
    /** 对应 C++ `ECharacterType iType; // 0xA8`，底层 4 字节 int，size: 4 bytes. */
    private CharacterType type;
    /** 对应 C++ `int iShadowSize; // 0xAC - SizeLevel`，size: 4 bytes. */
    private int shadowSize;
    /** 对应 C++ `EMonsterEffectID iMonsterEffectID; // 0xB0 - dwCharSoundCode`，size: 4 bytes. */
    private MonsterEffectId monsterEffectId;
    /** 对应 C++ `ECharacterClass iClass; // 0xB4 - JOB_CODE`，size: 4 bytes. */
    private CharacterClass clazz;
    /** 对应 C++ `int iLevel; // 0xB8 - Level`，size: 4 bytes. */
    private int level;
    /** 对应 C++ `int iStrength; // 0xBC - Strength`，size: 4 bytes. */
    private int strength;
    /** 对应 C++ `int iSpirit; // 0xC0 - Spirit`，size: 4 bytes. */
    private int spirit;
    /** 对应 C++ `int iTalent; // 0xC4 - Talent`，size: 4 bytes. */
    private int talent;
    /** 对应 C++ `int iAgility; // 0xC8 - Dexterity`，size: 4 bytes. */
    private int agility;
    /** 对应 C++ `int iHealth; // 0xCC - Health`，size: 4 bytes. */
    private int health;
    /** 对应 C++ `int iAccuracy; // 0xD0 - Accuracy`，size: 4 bytes. */
    private int accuracy;
    /** 对应 C++ `int iAttackRating; // 0xD4 - Attack_Rating`，size: 4 bytes. */
    private int attackRating;
    /** 对应 C++ `int iMinDamage; // 0xD8 - Attack_Damage[0]`，size: 4 bytes. */
    private int minDamage;
    /** 对应 C++ `int iMaxDamage; // 0xDC - Attack_Damage[1]`，size: 4 bytes. */
    private int maxDamage;
    /** 对应 C++ `int iAttackSpeed; // 0xE0 - Attack_Speed`，size: 4 bytes. */
    private int attackSpeed;
    /** 对应 C++ `int iAttackRange; // 0xE4 - Shooting_Range`，size: 4 bytes. */
    private int attackRange;
    /** 对应 C++ `int iCritical; // 0xE8 - Critical_Hit`，size: 4 bytes. */
    private int critical;
    /** 对应 C++ `int iDefenseRating; // 0xEC - Defence`，size: 4 bytes. */
    private int defenseRating;
    /** 对应 C++ `int iBlockRating; // 0xF0 - Chance_Block`，size: 4 bytes. */
    private int blockRating;
    /** 对应 C++ `int iAbsorbRating; // 0xF4 - Absorption`，size: 4 bytes. */
    private int absorbRating;
    /** 对应 C++ `int iMovementSpeed; // 0xF8 - Move_Speed`，size: 4 bytes. */
    private int movementSpeed;
    /**
     * 对应 C++ 联合体
     * `union { int iSight; // 0xFC; int iNPCId; }`，底层都是 4 字节 int，size: 4 bytes.
     * 这里命名为 sight，视作该联合体的原始值；如需以 NPC 运行时 ID 语义访问，可在业务层再封装。
     */
    private int sight;
    /** 对应 C++ `CurMax sWeight; // 0x100 - Weight[2]`，size: 4 bytes. */
    private CurMax weight;
    /** 对应 C++ `short sElementalDef[8]; // 0x104 (index 3 = ice, 4 = poison)`，size: 16 bytes. */
    private final short[] elementalDef = new short[8];
    /** 对应 C++ `short sElementalAtk[8]; // 0x114 - Attack_Resistance[8]`，size: 16 bytes. */
    private final short[] elementalAtk = new short[8];
    /** 对应 C++ `CurMax sHP; // 0x124 - Life[2]`，size: 4 bytes. */
    private CurMax hp;
    /** 对应 C++ `CurMax sMP; // 0x128 - Mana[2]`，size: 4 bytes. */
    private CurMax mp;
    /** 对应 C++ `CurMax sSP; // 0x12C - Stamina[2]`，size: 4 bytes. */
    private CurMax sp;
    /** 对应 C++ `float fHPRegen; // 0x130 - Life_Regen`，size: 4 bytes. */
    private float hpRegen;
    /** 对应 C++ `float fMPRegen; // 0x134 - Mana_Regen`，size: 4 bytes. */
    private float mpRegen;
    /** 对应 C++ `float fSPRegen; // 0x138 - Stamina_Regen`，size: 4 bytes. */
    private float spRegen;
    /** 对应 C++ `unsigned int iCurrentExpLow; // 0x13C - Exp`，size: 4 bytes. */
    private int currentExpLow;
    /** 对应 C++ `int iOwnerID; // 0x140`，size: 4 bytes. */
    private int ownerId;
    /** 对应 C++ `int iGold; // 0x144 - Money`，size: 4 bytes. */
    private int gold;
    /**
     * 对应 C++ `union { struct UnitInfo* psUnitInfo; int sBSAL; }; // 0x148`
     * 这里只保留为原始 int，size: 4 bytes.
     */
    private int sBSAL;
    /** 对应 C++ `EMonsterType iMonsterType; // 0x14C - Brood`，底层 4 字节 int，size: 4 bytes. */
    private MonsterType monsterType;
    /**
     * 对应 C++ 联合体
     * `union { int iStatPoints; int iUniqueMonsterID; }; // 0x150`
     * 统一使用一个字段，size: 4 bytes.
     */
    private int statPointsOrUniqueMonsterId;
    /** 对应 C++ `char cNewLoad; // 0x154`，单字节，size: 1 byte. */
    private byte newLoad;
    /** 对应 C++ `char cUnknown389103821; // 0x155`，单字节，size: 1 byte. */
    private byte unknown389103821;
    /** 对应 C++ `char cUnknown358923102; // 0x156`，单字节，size: 1 byte. */
    private byte unknown358923102;
    /** 对应 C++ `char cUnknown869023021; // 0x157`，单字节，size: 1 byte. */
    private byte unknown869023021;
    /** 对应 C++ `MinMax sViewBoxZoom; // 0x158 - ArrowPosi`，size: 4 bytes. */
    private MinMax viewBoxZoom;
    /** 对应 C++ `CurMax sUnitPotions; // Potion_Space / hp blocks`，size: 4 bytes. */
    private CurMax unitPotions;
    /** 对应 C++ `int iHPType; // 0x160 - LifeFunction`，size: 4 bytes. */
    private int hpType;
    /** 对应 C++ `int iMPType; // 0x164 - ManaFunction`，size: 4 bytes. */
    private int mpType;
    /** 对应 C++ `BOOL bSPType; // 0x168 - StaminaFunction`，底层 4 字节 int，size: 4 bytes. */
    private int spType;
    /** 对应 C++ `short DamageFunction[2]; // 0x16C (0 = melee 1 = long range)`，size: 4 bytes. */
    private final short[] damageFunction = new short[2];
    /** 对应 C++ `DWORD dwChecksum; // 0x170 - RefomCode`，size: 4 bytes. */
    private int checksum;
    /** 对应 C++ `ECharacterRank iRank; // 0x174 - ChangeJob`，size: 4 bytes. */
    private CharacterRank rank;
    /** 对应 C++ `EClassFlag iFlag; // 0x178 - JobBitMask`，size: 4 bytes. */
    private ClassFlag flag;
    /** 对应 C++ `short sWarpHomeTown; // 0x17C`，size: 2 bytes. */
    private short warpHomeTown;
    /** 对应 C++ `short sMapID; // 0x17E`，size: 2 bytes. */
    private short mapId;
    /**
     * 对应 C++ `EMonsterClass sMonsterClass; // 0x180`，底层 WORD(2 字节)，size: 2 bytes.
     */
    private MonsterClass monsterClass;
    /** 对应 C++ `WORD sSize; // 0x182 - Scale`，size: 2 bytes. */
    private short size;
    /** 对应 C++ `unsigned int iCurrentExpHigh; // 0x184 - Exp_High`，size: 4 bytes. */
    private int currentExpHigh;
    /** 对应 C++ `BOOL bCustomHead; // 0x188`，底层 4 字节 int，size: 4 bytes. */
    private int customHead;
    /** 对应 C++ `BOOL bExclusiveBoss;`，底层 4 字节 int，size: 4 bytes. */
    private int exclusiveBoss;
    /** 对应 C++ `BOOL bGrandFuryBoss;`，底层 4 字节 int，size: 4 bytes. */
    private int grandFuryBoss;
    /** 对应 C++ `int iEvasiveRating;`，size: 4 bytes. */
    private int evasiveRating;
    /** 对应 C++ `int iaUnused[13];`，size: 52 bytes. */
    private final int[] unused = new int[13];
    /** 对应 C++ `short saUnused;`，size: 2 bytes. */
    private short unusedShort;
    /** 对应 C++ `short bResetStatistics; // Used with Event Girl`，size: 2 bytes. */
    private short resetStatistics;

    // ==========================
    // 联合体视图辅助方法
    // ==========================

    /** 在 union 缓冲区内读取 C 风格字符串（逻辑参考 Packet.readCString）。 */
    private static String readCString(byte[] buf, int offset, int maxLen) {
        int end = offset;
        int limit = offset + maxLen;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        // 去掉末尾空白，与 Packet.readCString 行为保持一致
        return new String(buf, offset, end - offset).trim();
    }

    /** 在 union 缓冲区内写入定长 C 字符串（逻辑参考 Packet.writeCString）。*/
    private static void writeCString(byte[] buf, int offset, int maxLen, String s) {
        // 清零
        for (int i = 0; i < maxLen; i++) {
            buf[offset + i] = 0;
        }
        if (s == null) {
            return;
        }
        byte[] src = s.getBytes();
        int copy = Math.min(src.length, maxLen - 1);
        System.arraycopy(src, 0, buf, offset, copy);
        if (copy < maxLen) {
            buf[offset + copy] = 0;
        }
    }

    /** 在 union 中按小端读取 4 字节 int。 */
    private static int readIntLE(byte[] buf, int offset) {
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** 在 union 中按小端写入 4 字节 int。 */
    private static void writeIntLE(byte[] buf, int offset, int value) {
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    // ---------- Player 视图 ----------

    /** 对应 C++ Player.szBodyModel[64]。*/
    public String getPlayerBodyModel() {
        return readCString(union, 0, 64);
    }

    public void setPlayerBodyModel(String bodyModel) {
        writeCString(union, 0, 64, bodyModel);
    }

    /** 对应 C++ Player.szHeadModel[64]。*/
    public String getPlayerHeadModel() {
        return readCString(union, 64, 64);
    }

    public void setPlayerHeadModel(String headModel) {
        writeCString(union, 64, 64, headModel);
    }

    // ---------- Monster 视图 ----------

    /** 对应 C++ Monster.szBodyModel[64]。*/
    public String getMonsterBodyModel() {
        return readCString(union, 0, 64);
    }

    public void setMonsterBodyModel(String bodyModel) {
        writeCString(union, 0, 64, bodyModel);
    }

    /** 对应 C++ Monster.szHeadModel[60]。*/
    public String getMonsterHeadModel() {
        return readCString(union, 64, 60);
    }

    public void setMonsterHeadModel(String headModel) {
        writeCString(union, 64, 60, headModel);
    }

    /** 对应 C++ Monster.iMonsterID（位于偏移 64 + 60 = 124）。*/
    public int getMonsterId() {
        return readIntLE(union, 124);
    }

    public void setMonsterId(int monsterId) {
        writeIntLE(union, 124, monsterId);
    }

    // ---------- NPC 视图 ----------

    /** 对应 C++ NPC.szBodyModel[64]。*/
    public String getNpcBodyModel() {
        return readCString(union, 0, 64);
    }

    public void setNpcBodyModel(String bodyModel) {
        writeCString(union, 0, 64, bodyModel);
    }

    /** 对应 C++ NPC.szHeadModel[60]。*/
    public String getNpcHeadModel() {
        return readCString(union, 64, 60);
    }

    public void setNpcHeadModel(String headModel) {
        writeCString(union, 64, 60, headModel);
    }

    /** 对应 C++ NPC.iNPCID（偏移 124）。*/
    public int getNpcId() {
        return readIntLE(union, 124);
    }

    public void setNpcId(int npcId) {
        writeIntLE(union, 124, npcId);
    }

    // ---------- Pet 视图 ----------

    /** 对应 C++ Pet.szBodyModel[65]。*/
    public String getPetBodyModel() {
        return readCString(union, 0, 65);
    }

    public void setPetBodyModel(String bodyModel) {
        writeCString(union, 0, 65, bodyModel);
    }

    /** 对应 C++ Pet.szOwnerName[63]（偏移 65）。*/
    public String getPetOwnerName() {
        return readCString(union, 65, 63);
    }

    public void setPetOwnerName(String ownerName) {
        writeCString(union, 65, 63, ownerName);
    }

    public void readFrom(ByteBuffer in) {
        // char szName[32]
        name = Packet.readCString(in, 32);

        // 联合体的原始 128 字节
        in.get(union);

        // 0xA0 开始的一系列 4 字节字段和枚举（底层 int）
        id = in.getInt();
        clanId = in.getInt();
        type = CharacterType.fromValue(in.getInt());
        shadowSize = in.getInt();
        monsterEffectId = MonsterEffectId.fromValue(in.getInt());
        clazz = CharacterClass.fromValue(in.getInt());
        level = in.getInt();
        strength = in.getInt();
        spirit = in.getInt();
        talent = in.getInt();
        agility = in.getInt();
        health = in.getInt();
        accuracy = in.getInt();
        attackRating = in.getInt();
        minDamage = in.getInt();
        maxDamage = in.getInt();
        attackSpeed = in.getInt();
        attackRange = in.getInt();
        critical = in.getInt();
        defenseRating = in.getInt();
        blockRating = in.getInt();
        absorbRating = in.getInt();
        movementSpeed = in.getInt();
        sight = in.getInt();

        // CurMax sWeight;
        if (weight == null) {
            weight = new CurMax();
        }
        weight.readFrom(in);

        // short 数组
        for (int i = 0; i < elementalDef.length; i++) {
            elementalDef[i] = in.getShort();
        }
        for (int i = 0; i < elementalAtk.length; i++) {
            elementalAtk[i] = in.getShort();
        }

        // CurMax sHP/sMP/sSP
        if (hp == null) {
            hp = new CurMax();
        }
        hp.readFrom(in);
        if (mp == null) {
            mp = new CurMax();
        }
        mp.readFrom(in);
        if (sp == null) {
            sp = new CurMax();
        }
        sp.readFrom(in);

        // Regen & Exp/Owner/Gold
        hpRegen = in.getFloat();
        mpRegen = in.getFloat();
        spRegen = in.getFloat();
        currentExpLow = in.getInt();
        ownerId = in.getInt();
        gold = in.getInt();

        // union psUnitInfo/sBSAL
        sBSAL = in.getInt();

        monsterType = MonsterType.fromValue(in.getInt());
        statPointsOrUniqueMonsterId = in.getInt();

        // 单字节标志
        newLoad = in.get();
        unknown389103821 = in.get();
        unknown358923102 = in.get();
        unknown869023021 = in.get();

        // MinMax sViewBoxZoom
        if (viewBoxZoom == null) {
            viewBoxZoom = new MinMax();
        }
        viewBoxZoom.readFrom(in);

        // CurMax sUnitPotions
        if (unitPotions == null) {
            unitPotions = new CurMax();
        }
        unitPotions.readFrom(in);

        // HP/MP/SP 类型及伤害函数
        hpType = in.getInt();
        mpType = in.getInt();
        spType = in.getInt();

        for (int i = 0; i < damageFunction.length; i++) {
            damageFunction[i] = in.getShort();
        }

        checksum = in.getInt();
        rank = CharacterRank.fromValue(in.getInt());
        flag = ClassFlag.fromValue(in.getInt());
        warpHomeTown = in.getShort();
        mapId = in.getShort();

        int monsterClassValue = in.getShort() & 0xFFFF;
        monsterClass = MonsterClass.fromValue(monsterClassValue);

        size = in.getShort();
        currentExpHigh = in.getInt();
        customHead = in.getInt();
        exclusiveBoss = in.getInt();
        grandFuryBoss = in.getInt();
        evasiveRating = in.getInt();

        for (int i = 0; i < unused.length; i++) {
            unused[i] = in.getInt();
        }
        unusedShort = in.getShort();
        resetStatistics = in.getShort();
    }

    public void writeTo(ByteBuffer out) {
        // char szName[32]
        Packet.writeCString(out, name, 32);
        // 联合体原始 128 字节
        out.put(union);
        // 对齐 C++ 布局的所有 4/2/1 字节字段
        out.putInt(id);
        out.putInt(clanId);
        out.putInt(type != null ? type.getValue() : 0);
        out.putInt(shadowSize);
        out.putInt(monsterEffectId != null ? monsterEffectId.getValue() : 0);
        out.putInt(clazz != null ? clazz.getValue() : 0);
        out.putInt(level);
        out.putInt(strength);
        out.putInt(spirit);
        out.putInt(talent);
        out.putInt(agility);
        out.putInt(health);
        out.putInt(accuracy);
        out.putInt(attackRating);
        out.putInt(minDamage);
        out.putInt(maxDamage);
        out.putInt(attackSpeed);
        out.putInt(attackRange);
        out.putInt(critical);
        out.putInt(defenseRating);
        out.putInt(blockRating);
        out.putInt(absorbRating);
        out.putInt(movementSpeed);
        out.putInt(sight);

        // CurMax sWeight;
        (weight != null ? weight : new CurMax()).writeTo(out);

        for (short v : elementalDef) {
            out.putShort(v);
        }
        for (short v : elementalAtk) {
            out.putShort(v);
        }

        (hp != null ? hp : new CurMax()).writeTo(out);
        (mp != null ? mp : new CurMax()).writeTo(out);
        (sp != null ? sp : new CurMax()).writeTo(out);

        out.putFloat(hpRegen);
        out.putFloat(mpRegen);
        out.putFloat(spRegen);
        out.putInt(currentExpLow);
        out.putInt(ownerId);
        out.putInt(gold);
        out.putInt(sBSAL);

        out.putInt(monsterType != null ? monsterType.getValue() : 0);
        out.putInt(statPointsOrUniqueMonsterId);

        out.put(newLoad);
        out.put(unknown389103821);
        out.put(unknown358923102);
        out.put(unknown869023021);

        (viewBoxZoom != null ? viewBoxZoom : new MinMax()).writeTo(out);
        (unitPotions != null ? unitPotions : new CurMax()).writeTo(out);

        out.putInt(hpType);
        out.putInt(mpType);
        out.putInt(spType);

        for (short v : damageFunction) {
            out.putShort(v);
        }

        out.putInt(checksum);
        out.putInt(rank != null ? rank.getValue() : 0);
        out.putInt(flag != null ? flag.getValue() : 0);
        out.putShort(warpHomeTown);
        out.putShort(mapId);

        out.putShort((short) (monsterClass != null ? monsterClass.getValue() : 0));
        out.putShort(size);
        out.putInt(currentExpHigh);
        out.putInt(customHead);
        out.putInt(exclusiveBoss);
        out.putInt(grandFuryBoss);
        out.putInt(evasiveRating);

        for (int v : unused) {
            out.putInt(v);
        }
        out.putShort(unusedShort);
        out.putShort(resetStatistics);
    }
}
