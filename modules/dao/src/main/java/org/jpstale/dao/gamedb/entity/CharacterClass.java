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
@TableName(schema = "gamedb", value = "characterclass")
public class CharacterClass {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("id2")
    private Integer id2;
    @TableField("name")
    private String name;
    @TableField("shortname")
    private String shortName;
}
