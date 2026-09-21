package org.jpstale.dao.eventdb.entity;

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
@TableName(schema = "eventdb", value = "furyarenarewardtracker")
public class FuryArenaRewardTracker {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("furyeventid")
    private Integer furyEventId;
    @TableField("characterid")
    private Integer characterId;
}
