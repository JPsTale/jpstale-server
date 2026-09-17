package org.jpstale.dao.userdb.entity;

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
@TableName(schema = "userdb", value = "characterexpdef")
public class CharacterExpDef {

    @TableId(value = "level", type = IdType.INPUT)
    private Integer level;
    @TableField("exprequired")
    private Long expRequired;
}
