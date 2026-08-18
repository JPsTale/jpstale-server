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
@TableName(schema = "clandb", value = "prbbssub")
public class PrBbsSub {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("mindex")
    private Integer mindex;
    @TableField("pmno")
    private String pmNo;
    @TableField("userid")
    private String userId;
    @TableField("writename")
    private String writeName;
    @TableField("content")
    private String content;
    @TableField("regidate")
    private LocalDateTime regiDate;
    @TableField("regiip")
    private String regiIp;
    @TableField("username")
    private String userName;
}
