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
@TableName(schema = "gamedb", value = "monsterlist")
public class MonsterList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("name")
    private String name;
    @TableField("modelfile")
    private String modelFile;
    @TableField("level")
    private Integer level;
    @TableField("glow")
    private Integer glow;
    @TableField("size")
    private Double size;
    @TableField("cameray")
    private Integer cameraY;
    @TableField("cameraz")
    private Integer cameraZ;
    @TableField("monsterid")
    private Integer monsterId;
    @TableField("dropispublic")
    private Integer dropIsPublic;
    @TableField("dropquantity")
    private Integer dropQuantity;
    @TableField("spawntime")
    private String spawnTime;
    @TableField("spawnmin")
    private Integer spawnMin;
    @TableField("spawnmax")
    private Integer spawnMax;
    @TableField("inteligence")
    private Integer inteligence;
    @TableField("monstertype")
    private String monsterType;
    @TableField("viewsight")
    private Integer viewSight;
    @TableField("hp")
    private Integer hp;
    @TableField("exp")
    private String exp;
    @TableField("specialskilltype")
    private String specialSkillType;
    @TableField("specialskillhit")
    private String specialSkillHit;
    @TableField("specialhitrate")
    private Integer specialHitRate;
    @TableField("specialhitscope")
    private Integer specialHitScope;
    @TableField("specialhitpowermin")
    private Integer specialHitPowerMin;
    @TableField("specialhitpowermax")
    private Integer specialHitPowerMax;
    @TableField("atkpowmin")
    private Integer atkPowMin;
    @TableField("atkpowmax")
    private Integer atkPowMax;
    @TableField("absorb")
    private Integer absorb;
    @TableField("stunchance")
    private Integer stunChance;
    @TableField("block")
    private Integer block;
    @TableField("defense")
    private Integer defense;
    @TableField("attackspeed")
    private Integer attackSpeed;
    @TableField("attackrating")
    private Integer attackRating;
    @TableField("attackrange")
    private Integer attackRange;
    @TableField("perfectattackrate")
    private Integer perfectAttackRate;
    @TableField("sizeshadow")
    private String sizeShadow;
    @TableField("organic")
    private Integer organic;
    @TableField("lightning")
    private Integer lightning;
    @TableField("ice")
    private Integer ice;
    @TableField("fire")
    private Integer fire;
    @TableField("poison")
    private Integer poison;
    @TableField("magic")
    private Integer magic;
    @TableField("propertymon")
    private String propertyMon;
    @TableField("movespeed")
    private Integer moveSpeed;
    @TableField("potionpercent")
    private Integer potionPercent;
    @TableField("potions")
    private Integer potions;
    @TableField("effect")
    private String effect;
    @TableField("questitemdrop")
    private String questItemDrop;
    @TableField("questid")
    private Integer questId;
    @TableField("questmap")
    private Integer questMap;
    @TableField("stage")
    private String stage;
    @TableField("healthpoint")
    private Integer healthPoint;
}
