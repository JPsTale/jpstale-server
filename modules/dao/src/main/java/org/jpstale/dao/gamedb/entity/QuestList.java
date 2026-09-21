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
@TableName(schema = "gamedb", value = "questlist")
public class QuestList {

    @TableId("id")
    private Integer id;
    @TableField("name")
    private String name;
    @TableField("shortdescription")
    private String shortDescription;
    @TableField("description")
    private String description;
    @TableField("progresstext")
    private String progressText;
    @TableField("conclusiontext")
    private String conclusionText;
    @TableField("party")
    private Integer party;
    @TableField("multiple")
    private Integer multiple;
    @TableField("pvp")
    private Integer pvp;
    @TableField("minlevel")
    private Integer minLevel;
    @TableField("maxlevel")
    private Integer maxLevel;
    @TableField("maxduration")
    private Integer maxDuration;
    @TableField("durationtype")
    private Integer durationType;
    @TableField("waittime")
    private Integer waitTime;
    @TableField("mapid")
    private String mapId;
    @TableField("monsterid")
    private String monsterId;
    @TableField("requireditems")
    private String requiredItems;
    @TableField("questtype")
    private Integer questType;
    @TableField("requiredquestids")
    private String requiredQuestIds;
    @TableField("inclusionquestids")
    private String inclusionQuestIds;
    @TableField("npcid")
    private Integer npcId;
    @TableField("progressnpcid")
    private Integer progressNpcId;
    @TableField("conclusionnpcid")
    private Integer conclusionNpcId;
    @TableField("autostartquestid")
    private Integer autoStartQuestId;
    @TableField("classrestriction")
    private String classRestriction;
    @TableField("areatype")
    private Integer areaType;
    @TableField("minx")
    private Integer minX;
    @TableField("maxx")
    private Integer maxX;
    @TableField("minz")
    private Integer minZ;
    @TableField("maxz")
    private Integer maxZ;
    @TableField("radius")
    private Integer radius;
}
