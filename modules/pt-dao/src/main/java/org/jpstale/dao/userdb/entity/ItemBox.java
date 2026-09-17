package org.jpstale.dao.userdb.entity;

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
@TableName(schema = "userdb", value = "itembox")
public class ItemBox {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("itemcode")
    private String itemCode;
    @TableField("item")
    private String item;
    @TableField("itemspec")
    private Integer itemSpec;
    @TableField("coinshop")
    private Integer coinShop;
    @TableField("hasitem")
    private Integer hasItem;
    @TableField("datereceived")
    private LocalDateTime dateReceived;
}
