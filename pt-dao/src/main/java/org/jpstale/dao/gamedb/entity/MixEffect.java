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
@TableName(schema = "gamedb", value = "mixeffect")
public class MixEffect {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("code")
    private Integer code;
    @TableField("beforevalue")
    private String beforeValue;
    @TableField("aftervalue")
    private String afterValue;
}
