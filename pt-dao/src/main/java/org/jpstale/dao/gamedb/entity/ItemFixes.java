package org.jpstale.dao.gamedb.entity;

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
@TableName(schema = "gamedb", value = "itemfixes")
public class ItemFixes {

    @TableId("id")
    private Integer id;
    @TableField("name")
    private String name;
    @TableField("minlevel")
    private Integer minLevel;
    @TableField("maxlevel")
    private Integer maxLevel;
    @TableField("minrarity")
    private Integer minRarity;
    @TableField("maxrarity")
    private Integer maxRarity;
    @TableField("isprefix")
    private Integer isPrefix;
    @TableField("issuffix")
    private Integer isSuffix;
    @TableField("chance")
    private Integer chance;
    @TableField("axes")
    private Integer axes;
    @TableField("bows")
    private Integer bows;
    @TableField("javelins")
    private Integer javelins;
    @TableField("daggers")
    private Integer daggers;
    @TableField("wands")
    private Integer wands;
    @TableField("scythes")
    private Integer scythes;
    @TableField("swords")
    private Integer swords;
    @TableField("claws")
    private Integer claws;
    @TableField("hammers")
    private Integer hammers;
    @TableField("phantoms")
    private Integer phantoms;
    @TableField("armors")
    private Integer armors;
    @TableField("robes")
    private Integer robes;
    @TableField("boots")
    private Integer boots;
    @TableField("gauntlets")
    private Integer gauntlets;
    @TableField("bracelets")
    private Integer bracelets;
    @TableField("amulets")
    private Integer amulets;
    @TableField("rings")
    private Integer rings;
    @TableField("shields")
    private Integer shields;
    @TableField("orbs")
    private Integer orbs;
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
    @TableField("atkpowmin")
    private Integer atkPowMin;
    @TableField("atkpowmax")
    private Integer atkPowMax;
    @TableField("rangemin")
    private Integer rangeMin;
    @TableField("rangemax")
    private Integer rangeMax;
    @TableField("atkratingmin")
    private Integer atkRatingMin;
    @TableField("atkratingmax")
    private Integer atkRatingMax;
    @TableField("criticalmin")
    private Double criticalMin;
    @TableField("criticalmax")
    private Double criticalMax;
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
    @TableField("regenerationhpmin")
    private Double regenerationHpMin;
    @TableField("regenerationhpmax")
    private Double regenerationHpMax;
    @TableField("regenerationmpmin")
    private Double regenerationMpMin;
    @TableField("regenerationmpmax")
    private Double regenerationMpMax;
    @TableField("addhpmin")
    private Integer addHpMin;
    @TableField("addhpmax")
    private Integer addHpMax;
    @TableField("addmpmin")
    private Integer addMpMin;
    @TableField("addmpmax")
    private Integer addMpMax;
    @TableField("runspeedmin")
    private Double runSpeedMin;
    @TableField("runspeedmax")
    private Double runSpeedMax;
}
