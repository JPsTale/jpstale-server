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
@TableName(schema = "clandb", value = "chiplog")
public class ChipLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("cidx")
    private Integer cidx;
    @TableField("cserver")
    private Integer cServer;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("permi")
    private String permi;
    @TableField("regidate")
    private LocalDateTime regiDate;
}
