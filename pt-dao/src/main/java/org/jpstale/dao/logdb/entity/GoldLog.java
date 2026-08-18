package org.jpstale.dao.logdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName(schema = "logdb", value = "goldlog")
public class GoldLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("accountname")
    private String accountName;
    @TableField(exist = false)
    private Integer source;
    @TableField("gold")
    private Integer gold;
    @TableField("inventorygold")
    private Integer inventoryGold;
    @TableField("str")
    private String str;
    @TableField("date")
    private LocalDateTime date;
    @TableField("isgameserver")
    private Integer isGameServer;
}
