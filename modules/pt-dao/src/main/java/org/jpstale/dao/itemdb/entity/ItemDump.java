package org.jpstale.dao.itemdb.entity;

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
@TableName(schema = "itemdb", value = "itemdump")
public class ItemDump {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("charname")
    private String charName;
    @TableField("itemname")
    private String itemName;
    @TableField("itemlevel")
    private Integer itemLevel;
    @TableField("itemid")
    private Integer itemId;
    @TableField("itemtypeid")
    private Integer itemTypeId;
    @TableField("itembaseid")
    private Integer itemBaseId;
    @TableField("itemheader")
    private Integer itemHeader;
    @TableField("itemchecksum")
    private Integer itemChecksum;
    @TableField("itembackupheader")
    private Integer itemBackupHeader;
    @TableField("itembackupchecksum")
    private Integer itemBackupChecksum;
    @TableField("mixeffect")
    private Integer mixEffect;
    @TableField("mixid")
    private Integer mixId;
    @TableField("agelevel")
    private Integer ageLevel;
    @TableField("attackrange")
    private Integer attackRange;
    @TableField("attackspeed")
    private Integer attackSpeed;
    @TableField("attackrating")
    private Integer attackRating;
    @TableField("atkpowmin")
    private Short atkPowMin;
    @TableField("atkpowmax")
    private Short atkPowMax;
    @TableField("critical")
    private Integer critical;
    @TableField("absorb")
    private Double absorb;
    @TableField("defense")
    private Integer defense;
    @TableField("block")
    private Double block;
    @TableField("organic")
    private Short organic;
    @TableField("fire")
    private Short fire;
    @TableField("frost")
    private Short frost;
    @TableField("lighting")
    private Short lighting;
    @TableField("poison")
    private Short poison;
    @TableField("strengthreq")
    private Integer strengthReq;
    @TableField("spiritreq")
    private Integer spiritReq;
    @TableField("talentreq")
    private Integer talentReq;
    @TableField("agilityreq")
    private Integer agilityReq;
    @TableField("healthreq")
    private Integer healthReq;
    @TableField("mpregen")
    private Double mpRegen;
    @TableField("hpregen")
    private Double hpRegen;
    @TableField("spregen")
    private Double spRegen;
    @TableField("addhp")
    private Double addHp;
    @TableField("addmp")
    private Double addMp;
    @TableField("addsp")
    private Double addSp;
    @TableField("itemspec")
    private Integer itemSpec;
    @TableField("specabsorb")
    private Double specAbsorb;
    @TableField("specdef")
    private Integer specDef;
    @TableField("specblockrating")
    private Double specBlockRating;
    @TableField("specattackspeed")
    private Integer specAttackSpeed;
    @TableField("speccritical")
    private Integer specCritical;
    @TableField("specattackratingdiv")
    private Short specAttackRatingDiv;
    @TableField("specattackpowerdiv")
    private Short specAttackPowerDiv;
    @TableField("specmpregen")
    private Double specMpRegen;
    @TableField("spechpregen")
    private Double specHpRegen;
    @TableField("specspregen")
    private Double specSpRegen;
    @TableField("itemuniqueid")
    private Integer itemUniqueId;
    @TableField("saleprice")
    private Integer salePrice;
    @TableField("createddate")
    private Integer createdDate;
}
