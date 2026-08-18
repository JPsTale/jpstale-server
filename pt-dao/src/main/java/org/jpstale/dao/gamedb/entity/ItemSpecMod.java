package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.*;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "gamedb", value = "itemspecmod")
public class ItemSpecMod {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("addspecclass01")
    private Integer addSpecClass01;
    @TableField("addspecclass02")
    private Integer addSpecClass02;
    @TableField("addspecclass03")
    private Integer addSpecClass03;
    @TableField("addspecclass04")
    private Integer addSpecClass04;
    @TableField("addspecclass05")
    private Integer addSpecClass05;
    @TableField("addspecclass06")
    private Integer addSpecClass06;
    @TableField("addspecclass07")
    private Integer addSpecClass07;
    @TableField("addspecclass08")
    private Integer addSpecClass08;
    @TableField("addspecclass09")
    private Integer addSpecClass09;
    @TableField("addspecclass10")
    private Integer addSpecClass10;
    @TableField("itemtype")
    private String itemType;
    @TableField("percentreqstrength")
    private Integer percentReqStrength;
    @TableField("percentreqspirit")
    private Integer percentReqSpirit;
    @TableField("percentreqtalent")
    private Integer percentReqTalent;
    @TableField("percentreqagility")
    private Integer percentReqAgility;
    @TableField("percentreqhealth")
    private Integer percentReqHealth;
}
