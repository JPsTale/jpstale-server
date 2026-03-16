package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.*;

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
@TableName(schema = "gamedb", value = "map_boss_hour")
public class MapBossHour {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("boss_id")
    private Integer bossId;
    @TableField("hour")
    private Integer hour;
}
