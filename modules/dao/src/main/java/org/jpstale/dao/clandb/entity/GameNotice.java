package org.jpstale.dao.clandb.entity;

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
@TableName(schema = "clandb", value = "gamenotice")
public class GameNotice {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("server")
    private Integer server;
    @TableField("pflag")
    private Integer pFlag;
    @TableField("txt")
    private String txt;
    @TableField("flag")
    private Integer flag;
}
