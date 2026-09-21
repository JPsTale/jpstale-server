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
@TableName(schema = "gamedb", value = "mapindicator")
public class MapIndicator {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("mapid")
    private Integer mapId;
    @TableField("type")
    private Integer type;
    @TableField("value")
    private Integer value;
    @TableField("posx")
    private Integer posX;
    @TableField("posz")
    private Integer posZ;
    @TableField("angley")
    private Integer angleY;
}
