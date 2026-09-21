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
@TableName(schema = "gamedb", value = "questrewardlist")
public class QuestRewardList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("questid")
    private Integer questId;
    @TableField("name")
    private String name;
    @TableField("monsterquantities")
    private String monsterQuantities;
    @TableField("requireddropquantities")
    private String requiredDropQuantities;
    @TableField("expreward")
    private Long expReward;
    @TableField("exppotbonus")
    private Integer expPotBonus;
    @TableField("expleveldifference")
    private Integer expLevelDifference;
    @TableField("itemrewardselect")
    private Integer itemRewardSelect;
    @TableField("itemsreward")
    private String itemsReward;
    @TableField("itemsrewardquantities")
    private String itemsRewardQuantities;
    @TableField("extrarewardtype")
    private String extraRewardType;
    @TableField("extrarewardvalues")
    private String extraRewardValues;
    @TableField("timemultiplier")
    private Integer timeMultiplier;
}
