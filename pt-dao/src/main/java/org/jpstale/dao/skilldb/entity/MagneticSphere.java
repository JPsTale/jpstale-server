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
@TableName(schema = "skilldb", value = "magneticsphere")
public class MagneticSphere {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("skilllevel")
    private Integer skillLevel;
    @TableField("spheredmg")
    private Integer sphereDmg;
    @TableField("range")
    private Integer range;
    @TableField("attackinterval")
    private Integer attackInterval;
    @TableField("duration")
    private Integer duration;
    @TableField("mpusage")
    private Integer mpusage;
    @TableField("stmusage")
    private Integer stmusage;
    @TableField("createtime")
    private LocalDateTime createTime;
}
