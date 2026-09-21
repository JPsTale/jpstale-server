package org.jpstale.server.web.simulator;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 装备模拟器：随机骰出的装备实例。
 * <p>
 * 属性命名与展示面向模拟器，字段含义对应 sITEMINFO 的掷点结果。
 */
@Data
public class ItemInstance {

    private Integer idCode;
    private String name;
    private String category;
    private String group;

    // 基础属性
    private Integer weight;
    private Integer price;
    private Integer attackSpeed;
    private Integer range;
    private Integer criticalHit;
    private Integer shootingRange;
    private Double magicMastery;
    private Integer potionSpace;

    // 需求
    private Integer level;
    private Integer strength;
    private Integer spirit;
    private Integer talent;
    private Integer agility;
    private Integer health;

    // 掷点结果
    private Integer durabilityCurrent;
    private Integer durabilityMax;
    private Integer damageMin;
    private Integer damageMax;
    private Integer attackRating;
    private Double absorb;
    private Integer defence;
    private Double blockRating;
    private Double speed;
    private Double manaRegen;
    private Double lifeRegen;
    private Double staminaRegen;
    private Double increaseLife;
    private Double increaseMana;
    private Double increaseStamina;

    // 抗性
    private Integer organicResistance;
    private Integer fireResistance;
    private Integer frostResistance;
    private Integer lightningResistance;
    private Integer poisonResistance;

    // 职业特效
    private Long jobCodeMask;
    private List<String> jobNames = new ArrayList<>();
    private Double specAbsorb;
    private Integer specDefence;
    private Double specSpeed;
    private Double specMagicMastery;
    private Double specManaRegen;
    private Integer specLevAttackRating;

    // 锻造/合成状态
    private Integer agingLevel;
    private Integer agingExp;
    private Integer agingExpMax;
}
