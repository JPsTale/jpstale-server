package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.*;

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
@TableName(schema = "gamedb", value = "raritychance")
public class RarityChance {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("raritychancegroup")
    private Integer rarityChanceGroup;
    @TableField("rarity")
    private Integer rarity;
    @TableField("chance")
    private Integer chance;
}
