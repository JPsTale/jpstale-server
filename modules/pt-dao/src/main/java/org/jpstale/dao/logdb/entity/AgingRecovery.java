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
@TableName(schema = "logdb", value = "agingrecovery")
public class AgingRecovery {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("characterid")
    private Integer characterId;
    @TableField("date")
    private LocalDateTime date;
    @TableField("itemname")
    private String itemName;
    @TableField("agenumber")
    private Integer ageNumber;
    @TableField("code1")
    private Integer code1;
    @TableField("code2")
    private Integer code2;
    @TableField("daterecovered")
    private LocalDateTime dateRecovered;
}
