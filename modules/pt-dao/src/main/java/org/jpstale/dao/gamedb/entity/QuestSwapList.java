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
@TableName(schema = "gamedb", value = "questswaplist")
public class QuestSwapList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("questrace1")
    private Integer questRace1;
    @TableField("questrace2")
    private Integer questRace2;
    @TableField("questrace3")
    private Integer questRace3;
}
