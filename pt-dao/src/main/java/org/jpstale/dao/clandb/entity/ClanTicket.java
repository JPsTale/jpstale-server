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
@TableName(schema = "clandb", value = "clanticket")
public class ClanTicket {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("sno")
    private Integer sNo;
    @TableField("servername")
    private String serverName;
    @TableField("midx")
    private Integer midx;
    @TableField("clanname")
    private String clanName;
    @TableField("clanjang")
    private Integer clanJang;
    @TableField("clanimage")
    private String clanImage;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("gpcode")
    private String gpCode;
    @TableField("logontime")
    private LocalDateTime logonTime;
    @TableField("ip")
    private String ip;
    @TableField("rno")
    private Integer rNo;
    @TableField("flag")
    private Integer flag;
}
