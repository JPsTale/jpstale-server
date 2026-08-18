package org.jpstale.dao.clandb.entity;

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
@TableName(schema = "clandb", value = "clansubdeleted")
public class ClanSubDeleted {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("midx")
    private Integer midx;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("chtype")
    private Integer chType;
    @TableField("chlv")
    private Integer chLv;
    @TableField("clanname")
    private String clanName;
    @TableField("permi")
    private String permi;
    @TableField("joindate")
    private LocalDateTime joinDate;
    @TableField("delactive")
    private String delActive;
    @TableField("pflag")
    private Integer pFlag;
    @TableField("kflag")
    private Integer kFlag;
    @TableField("deldate")
    private LocalDateTime delDate;
    @TableField("delstate")
    private String delState;
    @TableField("delcase")
    private String delCase;
    @TableField("server")
    private Integer server;
}
