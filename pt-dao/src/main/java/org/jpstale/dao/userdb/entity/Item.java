package org.jpstale.dao.userdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色物品实例（userdb.item）—— 全字段水平展开，对齐 DDL 03-create-userdb-item.sql
 * <p>
 * location 约定（画布版）：
 *   0=背包大画布(12×12, slot=y*12+x, 0~143)
 *   1=仓库画布(9×9, slot=y*9+x, 0~80)
 *   2=装备栏(槽 1~13)
 *   6=备用武器槽(仅槽 1/2，W 切换)
 *   3/4/5=预留(邮件/拍卖/交易锁定)
 */
@Data
@TableName(schema = "userdb", value = "item")
public class Item {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("character_id")
    private Integer characterId;

    /** 0=背包 1=仓库 2=装备 6=备用武器（画布/槽位容器） */
    @TableField("location")
    private Short location;

    /** 画布线性格号(y*W+x) / 装备槽1~13 */
    @TableField("slot")
    private Short slot;

    @TableField("item_code")
    private Integer itemCode;

    /** 物品定义唯一主键（gamedb.itemlist.id），消除 idcode 非唯一歧义 */
    @TableField("itemlist_id")
    private Integer itemListId;

    @TableField("count")
    private Integer count;

    // ---- 头部/校验（防复制，可堆叠物恒 0）----
    @TableField("head")
    private Integer head;
    @TableField("dw_version")
    private Integer dwVersion;
    @TableField("dw_time")
    private Integer dwTime;
    @TableField("chksum")
    private Integer chksum;

    // ---- 掷点结果 ----
    @TableField("durability")
    private Short durability;
    @TableField("durability_max")
    private Short durabilityMax;

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

    @TableField("damage_min")
    private Short damageMin;
    @TableField("damage_max")
    private Short damageMax;
    @TableField("attack_rating")
    private Integer attackRating;
    @TableField("critical")
    private Integer critical;
    @TableField("shooting_range")
    private Integer shootingRange;
    @TableField("attack_speed")
    private Integer attackSpeed;
    @TableField("absorb")
    private Double absorb;
    @TableField("defence")
    private Integer defence;
    @TableField("block_rating")
    private Double blockRating;
    @TableField("speed")
    private Double speed;
    @TableField("mana_regen")
    private Double manaRegen;
    @TableField("life_regen")
    private Double lifeRegen;
    @TableField("stamina_regen")
    private Double staminaRegen;
    @TableField("increase_life")
    private Double increaseLife;
    @TableField("increase_mana")
    private Double increaseMana;
    @TableField("increase_stamina")
    private Double increaseStamina;

    // ---- 需求属性 ----
    @TableField("req_level")
    private Integer reqLevel;
    @TableField("req_strength")
    private Integer reqStrength;
    @TableField("req_spirit")
    private Integer reqSpirit;
    @TableField("req_talent")
    private Integer reqTalent;
    @TableField("req_agility")
    private Integer reqAgility;
    @TableField("req_health")
    private Integer reqHealth;

    @TableField("price")
    private Integer price;

    /** 职业特效掩码（0=无；命中则 JobItem 生效） */
    @TableField("job_code_mask")
    private Integer jobCodeMask;

    // ---- 职业特效增益 (JobItem / spec_*) ----
    @TableField("spec_absorb")
    private Double specAbsorb;
    @TableField("spec_defence")
    private Integer specDefence;
    @TableField("spec_speed")
    private Double specSpeed;
    @TableField("spec_block_rating")
    private Double specBlockRating;
    @TableField("spec_attack_speed")
    private Integer specAttackSpeed;
    @TableField("spec_critical")
    private Integer specCritical;
    @TableField("spec_shooting_range")
    private Integer specShootingRange;
    @TableField("spec_magic_mastery")
    private Double specMagicMastery;
    @TableField("spec_res_bionic")
    private Short specResBionic;
    @TableField("spec_res_earth")
    private Short specResEarth;
    @TableField("spec_res_fire")
    private Short specResFire;
    @TableField("spec_res_ice")
    private Short specResIce;
    @TableField("spec_res_lighting")
    private Short specResLighting;
    @TableField("spec_res_poison")
    private Short specResPoison;
    @TableField("spec_res_water")
    private Short specResWater;
    @TableField("spec_res_wind")
    private Short specResWind;
    @TableField("spec_lev_mana")
    private Integer specLevMana;
    @TableField("spec_lev_life")
    private Integer specLevLife;
    @TableField("spec_lev_attack_rating")
    private Integer specLevAttackRating;
    @TableField("spec_lev_damage_max")
    private Integer specLevDamageMax;
    @TableField("spec_lev_res_bionic")
    private Short specLevResBionic;
    @TableField("spec_lev_res_earth")
    private Short specLevResEarth;
    @TableField("spec_lev_res_fire")
    private Short specLevResFire;
    @TableField("spec_lev_res_ice")
    private Short specLevResIce;
    @TableField("spec_lev_res_lighting")
    private Short specLevResLighting;
    @TableField("spec_lev_res_poison")
    private Short specLevResPoison;
    @TableField("spec_lev_res_water")
    private Short specLevResWater;
    @TableField("spec_lev_res_wind")
    private Short specLevResWind;
    @TableField("spec_per_mana_regen")
    private Double specPerManaRegen;
    @TableField("spec_per_life_regen")
    private Double specPerLifeRegen;
    @TableField("spec_per_stamina_regen")
    private Double specPerStaminaRegen;

    // ---- 锻造/合成 ----
    @TableField("aging_num")
    private Short agingNum;
    @TableField("aging_num2")
    private Short agingNum2;
    @TableField("aging_exp")
    private Integer agingExp;
    @TableField("aging_exp_max")
    private Integer agingExpMax;
    @TableField("aging_protect")
    private Integer agingProtect;
    @TableField("craft_mask")
    private Integer craftMask;
    @TableField("kind_code")
    private Short kindCode;

    // ---- 其他状态 ----
    @TableField("special_flag")
    private Short specialFlag;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("delete_time")
    private LocalDateTime deleteTime;
}
