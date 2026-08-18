package org.jpstale.dao.serverdb.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "serverdb", value = "usersonline")
public class UsersOnline {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("charactername")
    private String characterName;
    @TableField("ip")
    private String ip;
    @TableField("characterclass")
    private Integer characterClass;
    @TableField("characterlevel")
    private Integer characterLevel;
    @TableField("ticket")
    private Integer ticket;
    @TableField("logintime")
    private LocalDateTime loginTime;
}
