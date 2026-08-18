package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "gamedb", value = "itemlistold")
public class ItemListOld {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("idcode")
    private Integer idCode;
    @TableField("name")
    private String name;
    @TableField("nullcode")
    private String nullcode;
    @TableField("codeimg1")
    private String codeImg1;
    @TableField("codeimg2")
    private String codeImg2;
    @TableField("width")
    private Integer width;
    @TableField("height")
    private Integer height;
    @TableField("dropfolder")
    private String dropFolder;
    @TableField("classitem")
    private Integer classItem;
    @TableField("modelposition")
    private Integer modelPosition;
    @TableField("sound")
    private Integer sound;
    @TableField("weaponclass")
    private Integer weaponClass;
    @TableField("questid")
    private Integer questId;
    @TableField("questr")
    private Integer questR;
    @TableField("questg")
    private Integer questG;
    @TableField("questb")
    private Integer questB;
    @TableField("questtransparency")
    private Integer questTransparency;
    @TableField("questflashingtime")
    private Integer questFlashingTime;
    @TableField("reqlevel")
    private Integer reqLevel;
    @TableField("reqstrength")
    private Integer reqStrengh;
    @TableField("reqspirit")
    private Integer reqSpirit;
    @TableField("reqtalent")
    private Integer reqTalent;
    @TableField("reqagility")
    private Integer reqAgility;
    @TableField("reqhealth")
    private Integer reqHealth;
    @TableField("integritymin")
    private Integer integrityMin;
    @TableField("integritymax")
    private Integer integrityMax;
    @TableField("weight")
    private Integer weight;
    @TableField("price")
    private Integer price;
    @TableField("organicmin")
    private Integer organicMin;
    @TableField("organicmax")
    private Integer organicMax;
    @TableField("firemin")
    private Integer fireMin;
    @TableField("firemax")
    private Integer fireMax;
    @TableField("frostmin")
    private Integer frostMin;
    @TableField("frostmax")
    private Integer frostMax;
    @TableField("lightningmin")
    private Integer lightningMin;
    @TableField("lightningmax")
    private Integer lightningMax;
    @TableField("poisonmin")
    private Integer poisonMin;
    @TableField("poisonmax")
    private Integer poisonMax;
    @TableField("atkpow1min")
    private Integer atkPow1Min;
    @TableField("atkpow1max")
    private Integer atkPow1Max;
    @TableField("atkpow2min")
    private Integer atkPow2Min;
    @TableField("atkpow2max")
    private Integer atkPow2Max;
    @TableField("range")
    private Integer range;
    @TableField("atkspeed")
    private Integer atkSpeed;
    @TableField("atkratingmin")
    private Integer atkRatingMin;
    @TableField("atkratingmax")
    private Integer atkRatingMax;
    @TableField("critical")
    private Integer critical;
    @TableField("blockmin")
    private Double blockMin;
    @TableField("blockmax")
    private Double blockMax;
    @TableField("absorbmin")
    private Double absorbMin;
    @TableField("absorbmax")
    private Double absorbMax;
    @TableField("defensemin")
    private Integer defenseMin;
    @TableField("defensemax")
    private Integer defenseMax;
    @TableField("potionspace")
    private Integer potionSpace;
    @TableField("potioncount")
    private Integer potionCount;
    @TableField("regenerationhpmin")
    private Double regenerationHpMin;
    @TableField("regenerationhpmax")
    private Double regenerationHpMax;
    @TableField("regenerationmpmin")
    private Double regenerationMpMin;
    @TableField("regenerationmpmax")
    private Double regenerationMpMax;
    @TableField("regenerationstmmin")
    private Double regenerationStmMin;
    @TableField("regenerationstmmax")
    private Double regenerationStmMax;
    @TableField("addhpmin")
    private Integer addHpMin;
    @TableField("addhpmax")
    private Integer addHpMax;
    @TableField("addmpmin")
    private Integer addMpMin;
    @TableField("addmpmax")
    private Integer addMpMax;
    @TableField("addstmmin")
    private Integer addStmMin;
    @TableField("addstmmax")
    private Integer addStmMax;
    @TableField("recoveryhpmin")
    private Integer recoveryHpMin;
    @TableField("recoveryhpmax")
    private Integer recoveryHpMax;
    @TableField("recoverympmin")
    private Integer recoveryMpMin;
    @TableField("recoverympmax")
    private Integer recoveryMpMax;
    @TableField("recoverystmmin")
    private Integer recoveryStmMin;
    @TableField("recoverystmmax")
    private Integer recoveryStmMax;
    @TableField("runspeedmin")
    private Double runSpeedMin;
    @TableField("runspeedmax")
    private Double runSpeedMax;
    @TableField("primaryspec")
    private Integer primarySpec;
    @TableField("addspecclass1")
    private Integer addSpecClass1;
    @TableField("addspecclass2")
    private Integer addSpecClass2;
    @TableField("addspecclass3")
    private Integer addSpecClass3;
    @TableField("addspecclass4")
    private Integer addSpecClass4;
    @TableField("addspecclass5")
    private Integer addSpecClass5;
    @TableField("addspecclass6")
    private Integer addSpecClass6;
    @TableField("addspecclass7")
    private Integer addSpecClass7;
    @TableField("addspecclass8")
    private Integer addSpecClass8;
    @TableField("addspecclass9")
    private Integer addSpecClass9;
    @TableField("addspecclass10")
    private Integer addSpecClass10;
    @TableField("addspecclass11")
    private Integer addSpecClass11;
    @TableField("addspecclass12")
    private Integer addSpecClass12;
    @TableField("addspecrunspeedmin")
    private Double addSpecRunSpeedMin;
    @TableField("addspecrunspeedmax")
    private Double addSpecRunSpeedMax;
    @TableField("addspecabsorbmin")
    private Double addSpecAbsorbMin;
    @TableField("addspecabsorbmax")
    private Double addSpecAbsorbMax;
    @TableField("addspecdefensemin")
    private Integer addSpecDefenseMin;
    @TableField("addspecdefensemax")
    private Integer addSpecDefenseMax;
    @TableField("addspecatkspeed")
    private Integer addSpecAtkSpeed;
    @TableField("addspeccritical")
    private Integer addSpecCritical;
    @TableField("addspecatkpowermin")
    private Integer addSpecAtkPowerMin;
    @TableField("addspecatkpowermax")
    private Integer addSpecAtkPowerMax;
    @TableField("addspecatkratingmin")
    private Integer addSpecAtkRatingMin;
    @TableField("addspecatkratingmax")
    private Integer addSpecAtkRatingMax;
    @TableField("addspechpregen")
    private Double addSpecHpRegen;
    @TableField("addspecmpregenmin")
    private Double addSpecMpRegenMin;
    @TableField("addspecmpregenmax")
    private Double addSpecMpRegenMax;
    @TableField("addspecstmregen")
    private Double addSpecStmRegen;
    @TableField("addspecblock")
    private Double addSpecBlock;
    @TableField("addspecrange")
    private Integer addSpecRange;
    @TableField("cannotdrop")
    private Integer cannotDrop;
}
