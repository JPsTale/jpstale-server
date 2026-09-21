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
@TableName(schema = "clandb", value = "clanmaincharchangelog")
public class ClanMainCharChangeLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("userid")
    private String userId;
    @TableField("beforecharname")
    private String beforeCharName;
    @TableField("aftercharname")
    private String afterCharName;
    @TableField("cserver")
    private Integer cServer;
    @TableField("regidate")
    private LocalDateTime regiDate;
}
