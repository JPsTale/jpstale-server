package org.jpstale.dao.skilldb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "skilldb", value = "chargingstrike")
public class ChargingStrike {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("skilllevel")
    private Integer skillLevel;
    @TableField("damageboost")
    private Integer damageBoost;
    @TableField("chargeddamage")
    private Integer chargedDamage;
    @TableField("raisespeed")
    private Integer raiseSpeed;
    @TableField("mpusage")
    private Integer mpUsage;
    @TableField("stmusage")
    private Integer stmUsage;
    @TableField("createtime")
    private LocalDateTime createTime;
}
