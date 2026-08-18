package org.jpstale.server.game.model;

import lombok.Data;

/**
 * 物品模板 — 对应 gamedb.item_list
 */
@Data
public class ItemTemplate {

    private int id;
    private int idCode;
    private String name;
    private String nullcode;
    private String codeImg1;
    private String codeImg2;
    private int classItem;       // 物品大类 (武器/防具/消耗品等)
    private int weaponClass;     // 武器类型
    private int reqLevel;
    private int reqStrength;
    private int reqSpirit;
    private int reqTalent;
    private int reqAgility;
    private int reqHealth;
    private int weight;
    private int price;
    private int atkPow1Min;
    private int atkPow1Max;
    private int atkPow2Min;
    private int atkPow2Max;
    private int range;
    private int atkSpeed;
    private int atkRatingMin;
    private int atkRatingMax;
    private int critical;
    private double blockMin;
    private double blockMax;
    private double absorbMin;
    private double absorbMax;
    private int defenseMin;
    private int defenseMax;
    private int potionSpace;
    private int potionCount;
    private double regenerationHpMin;
    private double regenerationHpMax;
    private double regenerationMpMin;
    private double regenerationMpMax;
    private double regenerationStmMin;
    private double regenerationStmMax;
    private int addHpMin;
    private int addHpMax;
    private int addMpMin;
    private int addMpMax;
    private int addStmMin;
    private int addStmMax;
    private double runSpeedMin;
    private double runSpeedMax;
    private int cannotDrop;
}
