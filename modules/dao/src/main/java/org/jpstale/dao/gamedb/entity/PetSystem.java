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
@TableName(schema = "gamedb", value = "petsystem")
public class PetSystem {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("petownername")
    private String petOwnerName;
    @TableField("petname")
    private String petName;
    @TableField("petskinid")
    private Integer petSkinId;
}
