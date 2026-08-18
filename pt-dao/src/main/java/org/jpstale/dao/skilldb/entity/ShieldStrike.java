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
@TableName(schema = "skilldb", value = "shieldstrike")
public class ShieldStrike {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("skilllevel")
    private Integer skillLevel;
    @TableField("damagemin")
    private Integer damageMin;
    @TableField("damagemax")
    private Integer damageMax;
    @TableField("mpusage")
    private Integer mpusage;
    @TableField("stmusage")
    private Integer stmusage;
    @TableField("createtime")
    private LocalDateTime createTime;
}
