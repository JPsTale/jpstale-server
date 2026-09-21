package org.jpstale.dao.clandb.entity;

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
@TableName(schema = "clandb", value = "siegecurrentlist")
public class SiegeCurrentList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("cidx")
    private Integer cidx;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("servername")
    private String serverName;
    @TableField("operator")
    private String operator;
    @TableField("opercode")
    private String operCode;
    @TableField("siegemoney")
    private Long siegeMoney;
    @TableField("registday")
    private LocalDateTime registDay;
}
