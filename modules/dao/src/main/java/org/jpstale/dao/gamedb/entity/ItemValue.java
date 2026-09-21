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
@TableName(schema = "gamedb", value = "itemvalue")
public class ItemValue {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("itemlistid")
    private Integer itemListId;
    @TableField("attributeid")
    private Integer attributeId;
    @TableField("minvalue")
    private Double minValue;
    @TableField("maxvalue")
    private Double maxValue;
    @TableField("spec")
    private Integer spec;
}
