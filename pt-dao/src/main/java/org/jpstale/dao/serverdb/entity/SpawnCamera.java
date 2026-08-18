package org.jpstale.dao.serverdb.entity;

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
@TableName(schema = "serverdb", value = "spawncamera")
public class SpawnCamera {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("mapnumber")
    private Integer mapNumber;
    @TableField("spawnx")
    private Integer spawnX;
    @TableField("spawny")
    private Integer spawnY;
    @TableField("spawnz")
    private Integer spawnZ;
    @TableField("camxcoord")
    private Integer camXCoord;
    @TableField("camzcoord")
    private Integer camZCoord;
    @TableField("camturn")
    private Integer camTurn;
    @TableField("camangle")
    private Integer camAngle;
    @TableField("camzoom")
    private Integer camZoom;
    @TableField("minplayercount")
    private Integer minPlayerCount;
    @TableField("searchradius")
    private Integer searchRadius;
    @TableField("spawntype")
    private Integer spawnType;
}
