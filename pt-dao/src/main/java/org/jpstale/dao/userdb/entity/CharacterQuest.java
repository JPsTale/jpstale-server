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
@TableName(schema = "userdb", value = "characterquest")
public class CharacterQuest {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("questid")
    private Integer questId;
    @TableField("questrewardid")
    private Integer questRewardId;
    @TableField("characterid")
    private Integer characterId;
    @TableField("accountname")
    private String accountName;
    @TableField("charactername")
    private String characterName;
    @TableField("startdate")
    private LocalDateTime startDate;
    @TableField("enddate")
    private LocalDateTime endDate;
    @TableField("monstercount")
    private String monsterCount;
    @TableField("itemcount")
    private String itemCount;
    @TableField("timeleft")
    private Integer timeLeft;
    @TableField("counter")
    private Integer counter;
}
