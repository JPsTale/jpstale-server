package org.jpstale.dao.userdb.entity;

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
@TableName(schema = "userdb", value = "characterinfo")
public class CharacterInfo {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("name")
    private String name;
    @TableField("oldhead")
    private String oldHead;
    @TableField("level")
    private Integer level;
    @TableField("experience")
    private Long experience;
    @TableField("gold")
    private Integer gold;
    @TableField("jobcode")
    private Integer jobCode;
    @TableField("clanid")
    private Integer clanId;
    @TableField("clanpermission")
    private Integer clanPermission;
    @TableField("clanleavedate")
    private Integer clanLeaveDate;
    @TableField("lastseendate")
    private LocalDateTime lastSeenDate;
    @TableField("blesscastlescore")
    private Integer blessCastleScore;
    @TableField("fsp")
    private Integer fsp;
    @TableField("laststage")
    private Integer lastStage;
    @TableField("isonline")
    private Integer isOnline;
    @TableField("seasonal")
    private Integer seasonal;
    @TableField("gmlevel")
    private Integer gmLevel;
    @TableField("banned")
    private Integer banned;
    @TableField("title")
    private Integer title;
    @TableField("dialogskin")
    private Integer dialogSkin;
    @TableField(exist = false)
    private LocalDateTime levelUpDate;
}
