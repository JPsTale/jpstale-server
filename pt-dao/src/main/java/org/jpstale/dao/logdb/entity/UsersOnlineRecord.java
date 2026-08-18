package org.jpstale.dao.logdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName(schema = "logdb", value = "usersonlinerecord")
public class UsersOnlineRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("usersonlinesubserver1")
    private Integer usersOnlineSubServer1;
    @TableField("usersonlinesubserver2")
    private Integer usersOnlineSubServer2;
    @TableField("usersonlinesubserver3")
    private Integer usersOnlineSubServer3;
    @TableField("totalusersonline")
    private Integer totalUsersOnline;
    @TableField("date")
    private LocalDateTime date;
}
