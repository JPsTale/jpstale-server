package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName(schema = "gamedb", value = "coinshopitem")
public class CoinShopItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("tabid")
    private Integer tabId;
    @TableField("name")
    private String name;
    @TableField("description")
    private String description;
    @TableField("code")
    private String code;
    @TableField("image")
    private String image;
    @TableField("value")
    private Integer value;
    @TableField("discount")
    private Integer discount;
    @TableField("bulk")
    private Integer bulk;
    @TableField("maxbulk")
    private Integer maxBulk;
    @TableField("isspec")
    private Integer isSpec;
    @TableField("isquantity")
    private Integer isQuantity;
    @TableField("disabled")
    private Integer disabled;
    @TableField("listorder")
    private Integer listOrder;
}
