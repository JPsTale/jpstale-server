package org.jpstale.dao.clandb.entity;

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
@TableName(schema = "clandb", value = "bellatrateamscore")
public class BellatraTeamScore {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("charactername")
    private String characterName;
    @TableField("jobcode")
    private Integer jobCode;
    @TableField("level")
    private Integer level;
    @TableField("clancode")
    private Integer clanCode;
    @TableField("score")
    private Integer score;
    @TableField("kills")
    private Integer kills;
    @TableField("totalpoint")
    private Integer totalPoint;
    @TableField("totaluser")
    private Integer totalUser;
    @TableField("successuser")
    private Integer successUser;
    @TableField("code")
    private Integer code;
    @TableField("quake")
    private Integer quake;
    @TableField("stunseal")
    private Integer stunSeal;
    @TableField("freezeseal")
    private Integer freezeSeal;
    @TableField("rabieseal")
    private Integer rabieSeal;
    @TableField("stygianseal")
    private Integer stygianSeal;
    @TableField("guardiansaintseal")
    private Integer guardianSaintSeal;
    @TableField("pointbag")
    private Integer pointBag;
    @TableField("date")
    private String date;
}
