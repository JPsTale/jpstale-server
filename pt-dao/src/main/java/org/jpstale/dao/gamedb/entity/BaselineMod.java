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
@TableName(schema = "gamedb", value = "baselinemod")
public class BaselineMod {

    @TableId("clazz")
    private Integer clazz;
    @TableField("percentbaselinestrength")
    private Integer percentBaselineStrength;
    @TableField("percentbaselinespirit")
    private Integer percentBaselineSpirit;
    @TableField("percentbaselinetalent")
    private Integer percentBaselineTalent;
    @TableField("percentbaselineagility")
    private Integer percentBaselineAgility;
    @TableField("percentbaselinehealth")
    private Integer percentBaselineHealth;
}
