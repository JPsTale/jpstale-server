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
@TableName(schema = "gamedb", value = "coinshoptab")
public class CoinShopTab {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("coinshopid")
    private Integer coinShopId;
    @TableField("name")
    private String name;
    @TableField("parentid")
    private Integer parentId;
    @TableField("discount")
    private Integer discount;
    @TableField("bulk")
    private Integer bulk;
    @TableField("maxbulk")
    private Integer maxBulk;
    @TableField("listorder")
    private Integer listOrder;
}
