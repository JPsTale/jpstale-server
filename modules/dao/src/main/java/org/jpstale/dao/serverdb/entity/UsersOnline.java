package org.jpstale.dao.serverdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
