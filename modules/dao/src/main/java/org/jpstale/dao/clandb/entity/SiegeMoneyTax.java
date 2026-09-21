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
@TableName(schema = "clandb", value = "siegemoneytax")
public class SiegeMoneyTax {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("dno")
    private Integer dNo;
    @TableField("mixing")
    private Long mixing;
    @TableField("aging")
    private Long aging;
    @TableField("shop")
    private Long shop;
    @TableField("poison1")
    private Long poison1;
    @TableField("poison2")
    private Long poison2;
    @TableField("poison3")
    private Long poison3;
    @TableField("force")
    private Long force;
    @TableField("warpgate")
    private Long warpgate;
    @TableField("skill")
    private Long skill;
    @TableField("total")
    private Long total;
    @TableField("tax")
    private Long tax;
    @TableField("servername")
    private String serverName;
    @TableField("registday")
    private LocalDateTime registDay;
}
