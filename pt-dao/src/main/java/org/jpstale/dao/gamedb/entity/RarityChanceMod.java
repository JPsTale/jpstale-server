package org.jpstale.dao.gamedb.entity;

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
@TableName(schema = "gamedb", value = "raritychancemod")
public class RarityChanceMod {

    @TableId("type")
    private Integer type;
    @TableField("modcommon")
    private Double modCommon;
    @TableField("moduncommon")
    private Double modUncommon;
    @TableField("modrare")
    private Double modRare;
    @TableField("modepic")
    private Double modEpic;
    @TableField("modlegendary")
    private Double modLegendary;
}
