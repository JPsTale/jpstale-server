package org.jpstale.dao.gamedb.entity;

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
@TableName(schema = "gamedb", value = "modelanimationlist")
public class ModelAnimationList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("modelid")
    private Integer modelId;
    @TableField("type")
    private Integer type;
    @TableField("framebegin")
    private Integer frameBegin;
    @TableField("frameend")
    private Integer frameEnd;
    @TableField("frameevent1")
    private Integer frameEvent1;
    @TableField("frameevent2")
    private Integer frameEvent2;
    @TableField("frameevent3")
    private Integer frameEvent3;
    @TableField("frameevent4")
    private Integer frameEvent4;
}
