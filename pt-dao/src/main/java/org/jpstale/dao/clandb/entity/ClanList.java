package org.jpstale.dao.clandb.entity;

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
@TableName(schema = "clandb", value = "clanlist")
public class ClanList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("clanname")
    private String clanName;
    @TableField("clanleader")
    private String clanLeader;
    @TableField("note")
    private String note;
    @TableField("accountname")
    private String accountName;
    @TableField("memberscount")
    private Integer membersCount;
    @TableField("iconid")
    private Integer iconId;
    @TableField("regisdate")
    private Integer regisDate;
    @TableField("limitdate")
    private Integer limitDate;
    @TableField("deleteactive")
    private Integer deleteActive;
    @TableField("flag")
    private Integer flag;
    @TableField("siegewarpoints")
    private Integer siegeWarPoints;
    @TableField("bellatrapoints")
    private Integer bellatraPoints;
    @TableField("siegewargold")
    private Integer siegeWarGold;
    @TableField("bellatragold")
    private Integer bellatraGold;
    @TableField("bellatradate")
    private Long bellatraDate;
    @TableField("loginmessage")
    private String loginMessage;
}
