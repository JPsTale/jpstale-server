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
@TableName(schema = "gamedb", value = "agelist")
public class AgeList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("agenumber")
    private Integer ageNumber;
    @TableField("failchance")
    private Integer failChance;
    @TableField("plus2chance")
    private Integer plus2Chance;
    @TableField("minus2chance")
    private Integer minus2Chance;
    @TableField("minus1chance")
    private Integer minus1Chance;
    @TableField("brokenchance")
    private Integer brokenChance;
    @TableField("agestone")
    private Integer ageStone;
}
