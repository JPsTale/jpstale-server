package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "gamedb", value = "mapspawnpoint")
public class MapSpawnPoint {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("stage")
    private Integer stage;
    @TableField("x")
    private Integer x;
    @TableField("z")
    private Integer z;
    @TableField("description")
    private String description;
}
