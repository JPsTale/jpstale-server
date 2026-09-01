package org.jpstale.dao.userdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色物品实例（userdb.item）
 * <p>
 * location 约定：0=背包，1=装备栏（slot=装备槽位），2=仓库
 */
@Data
@TableName(schema = "userdb", value = "item")
public class Item {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("character_id")
    private Integer characterId;

    /** 0=背包，1=装备栏，2=仓库 */
    @TableField("location")
    private Short location;

    @TableField("slot")
    private Short slot;

    @TableField("item_code")
    private Integer itemCode;

    /** 物品定义唯一主键（gamedb.itemlist.id），消除 idcode 非唯一歧义 */
    @TableField("itemlist_id")
    private Integer itemListId;

    @TableField("count")
    private Integer count;

    @TableField("durability")
    private Short durability;

    @TableField("durability_max")
    private Short durabilityMax;

    @TableField("damage_min")
    private Short damageMin;

    @TableField("damage_max")
    private Short damageMax;

    @TableField("attack_rating")
    private Integer attackRating;

    @TableField("absorb")
    private Double absorb;

    @TableField("defence")
    private Integer defence;

    @TableField("block_rating")
    private Double blockRating;

    @TableField("res_bionic")
    private Short resBionic;

    @TableField("res_earth")
    private Short resEarth;

    @TableField("res_fire")
    private Short resFire;

    @TableField("res_ice")
    private Short resIce;

    @TableField("res_lighting")
    private Short resLighting;

    @TableField("res_poison")
    private Short resPoison;

    @TableField("res_water")
    private Short resWater;

    @TableField("res_wind")
    private Short resWind;
}
