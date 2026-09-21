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
