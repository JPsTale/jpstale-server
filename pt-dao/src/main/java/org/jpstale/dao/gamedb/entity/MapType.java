package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.*;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "gamedb", value = "map_type")
public class MapType {

    @TableId(value = "id")
    private Integer id;
    @TableField("name")
    private String name;
}
