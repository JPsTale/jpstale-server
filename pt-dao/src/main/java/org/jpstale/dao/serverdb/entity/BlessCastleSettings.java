package org.jpstale.dao.serverdb.entity;

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
@TableName(schema = "serverdb", value = "blesscastlesettings")
public class BlessCastleSettings {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("clanid1")
    private Integer clanId1;
    @TableField("clanid2")
    private Integer clanId2;
    @TableField("clanid3")
    private Integer clanId3;
    @TableField("tax")
    private Integer tax;
    @TableField("skill")
    private Integer skill;
    @TableField("tower1type")
    private Integer tower1Type;
    @TableField("tower2type")
    private Integer tower2Type;
    @TableField("tower3type")
    private Integer tower3Type;
    @TableField("tower4type")
    private Integer tower4Type;
    @TableField("tower5type")
    private Integer tower5Type;
    @TableField("tower6type")
    private Integer tower6Type;
    @TableField("guard1amount")
    private Integer guard1Amount;
    @TableField("guard2amount")
    private Integer guard2Amount;
    @TableField("guard3amount")
    private Integer guard3Amount;
}
