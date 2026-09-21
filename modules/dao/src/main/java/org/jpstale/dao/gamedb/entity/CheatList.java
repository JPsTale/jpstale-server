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
@TableName(schema = "gamedb", value = "cheatlist")
public class CheatList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("cheattype")
    private Integer cheatType;
    @TableField("cheatid")
    private Integer cheatId;
    @TableField("cheatname")
    private String cheatName;
    @TableField("cheatsignature")
    private Integer cheatSignature;
}
