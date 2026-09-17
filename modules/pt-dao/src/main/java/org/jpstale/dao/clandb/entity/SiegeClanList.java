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
@TableName(schema = "clandb", value = "siegeclanlist")
public class SiegeClanList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("userid")
    private String userId;
    @TableField("charname")
    private String charName;
    @TableField("clanname")
    private String clanName;
    @TableField("taxrate")
    private Integer taxRate;
    @TableField("servername")
    private String serverName;
    @TableField("server")
    private Integer server;
    @TableField("operfrom")
    private LocalDateTime operFrom;
    @TableField("operto")
    private LocalDateTime operTo;
    @TableField("registday")
    private LocalDateTime registDay;
}
