package org.jpstale.dao.userdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.Instant;
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
@TableName(schema = "userdb", value = "userinfo")
public class UserInfo {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("password")
    private String password;
    /** 注册时间，UTC 存库，展示时按用户时区格式化 */
    @TableField("regisday")
    private Instant regisDay;
    @TableField("flag")
    private Integer flag;
    @TableField("active")
    private Integer active;
    @TableField("activecode")
    private String activeCode;
    @TableField("coins")
    private Integer coins;
    @TableField("email")
    private String email;
    @TableField("gamemastertype")
    private Integer gameMasterType;
    @TableField("gamemasterlevel")
    private Integer gameMasterLevel;
    @TableField("gamemastermacaddress")
    private String gameMasterMacAddress;
    @TableField("coinstraded")
    private Integer coinsTraded;
    @TableField("banstatus")
    private Integer banStatus;
    @TableField("unbandate")
    private LocalDateTime unbanDate;
    @TableField("ismuted")
    private Integer isMuted;
    @TableField("mutecount")
    private Integer muteCount;
    @TableField("unmutedate")
    private LocalDateTime unmuteDate;
    /** Web 系统管理员：true 可访问 /api/admin/** */
    @TableField(exist = false)
    private Boolean webAdmin;
}
