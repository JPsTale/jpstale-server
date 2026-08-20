package org.jpstale.server.web.simulator;

import lombok.Data;

/**
 * 装备模拟器：物品详情（含掷点区间，用于展示与随机骰）。
 */
@Data
public class ItemDetail {

    private Integer id;
    private Integer idCode;
    private String name;
    private String category;
    private String group;

    // 基础
    private Integer width;
    private Integer height;
    private Integer weight;
    private Integer price;
    private Integer weaponClass;
    private Integer classItem;
    private Integer reqLevel;
    private Integer reqStrength;
    private Integer reqSpirit;
    private Integer reqTalent;
    private Integer reqAgility;
    private Integer reqHealth;
    private Integer atkSpeed;
    private Integer range;
    private Integer critical;
    private Integer potionSpace;
    private Integer potionCount;
    private Integer primarySpec;
    private Integer cannotDrop;

    // 掷点区间 (min/max)
    private Integer integrityMin;
    private Integer integrityMax;
    private Integer organicMin;
    private Integer organicMax;
    private Integer fireMin;
    private Integer fireMax;
    private Integer frostMin;
    private Integer frostMax;
    private Integer lightningMin;
    private Integer lightningMax;
    private Integer poisonMin;
    private Integer poisonMax;
    private Integer atkPow1Min;
    private Integer atkPow1Max;
    private Integer atkPow2Min;
    private Integer atkPow2Max;
    private Integer atkRatingMin;
    private Integer atkRatingMax;
    private Double blockMin;
    private Double blockMax;
    private Double absorbMin;
    private Double absorbMax;
    private Integer defenseMin;
    private Integer defenseMax;
    private Double runSpeedMin;
    private Double runSpeedMax;
    private Integer addHpMin;
    private Integer addHpMax;
    private Integer addMpMin;
    private Integer addMpMax;
    private Integer addStmMin;
    private Integer addStmMax;
    private Double regenerationHpMin;
    private Double regenerationHpMax;
    private Double regenerationMpMin;
    private Double regenerationMpMax;
    private Double regenerationStmMin;
    private Double regenerationStmMax;

    // 职业特效区间
    private Integer addSpecClass1;
    private Integer addSpecClass2;
    private Integer addSpecClass3;
    private Integer addSpecClass4;
    private Integer addSpecClass5;
    private Integer addSpecClass6;
    private Integer addSpecClass7;
    private Integer addSpecClass8;
    private Integer addSpecClass9;
    private Integer addSpecClass10;
    private Integer addSpecClass11;
    private Integer addSpecClass12;
    private Double addSpecRunSpeedMin;
    private Double addSpecRunSpeedMax;
    private Double addSpecAbsorbMin;
    private Double addSpecAbsorbMax;
    private Integer addSpecDefenseMin;
    private Integer addSpecDefenseMax;
    private Integer addSpecAtkSpeed;
    private Integer addSpecCritical;
    private Integer addSpecAtkPowerMin;
    private Integer addSpecAtkPowerMax;
    private Integer addSpecAtkRatingMin;
    private Integer addSpecAtkRatingMax;
    private Double addSpecHpRegen;
    private Double addSpecMpRegenMin;
    private Double addSpecMpRegenMax;
    private Double addSpecStmRegen;
    private Double addSpecBlock;
    private Integer addSpecRange;
}
