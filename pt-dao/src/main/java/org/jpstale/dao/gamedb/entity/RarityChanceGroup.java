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
@TableName(schema = "gamedb", value = "raritychancegroup")
public class RarityChanceGroup {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("minlevel")
    private Integer minLevel;
    @TableField("maxlevel")
    private Integer maxLevel;
}
