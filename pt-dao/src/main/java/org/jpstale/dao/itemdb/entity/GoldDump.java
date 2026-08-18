package org.jpstale.dao.itemdb.entity;

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
@TableName(schema = "itemdb", value = "golddump")
public class GoldDump {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("charname")
    private String charName;
    @TableField("warehousegold")
    private Integer warehouseGold;
    @TableField("charactergold")
    private Integer characterGold;
    @TableField("datetime")
    private LocalDateTime dateTime;
}
