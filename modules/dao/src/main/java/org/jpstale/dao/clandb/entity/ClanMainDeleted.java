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
@TableName(schema = "clandb", value = "clanmaindeleted")
public class ClanMainDeleted {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("midx")
    private Integer midx;
    @TableField("clanname")
    private String clanName;
    @TableField("userid")
    private String userId;
    @TableField("clanzang")
    private String clanZang;
    @TableField("flag")
    private Integer flag;
    @TableField("memcnt")
    private Integer memCnt;
    @TableField("miconcnt")
    private Integer mIconCnt;
    @TableField("regidate")
    private LocalDateTime regiDate;
    @TableField("limitdate")
    private LocalDateTime limitDate;
    @TableField("deldate")
    private LocalDateTime delDate;
    @TableField("delactive")
    private String delActive;
    @TableField("pflag")
    private Integer pFlag;
    @TableField("kflag")
    private Integer kFlag;
    @TableField("delstate")
    private String delState;
    @TableField("delcase")
    private String delCase;
    @TableField("server")
    private Integer server;
}
