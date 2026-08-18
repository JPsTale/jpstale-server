package org.jpstale.dao.itemdb.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "itemdb", value = "itembase")
public class ItemBase {

    @TableId("item_base_id")
    private Integer itemBaseId;
    @TableField("itembasename")
    private String itemBaseName;
    @TableField("itembasehex")
    private byte[] itemBaseHex;
    @TableField("itembaseabbrv")
    private String itemBaseAbbrv;
}
