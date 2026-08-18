package org.jpstale.dao.userdb.entity;

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
@TableName(schema = "userdb", value = "charactermonsterkilltracker")
public class CharacterMonsterKillTracker {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("characterid")
    private Integer characterId;
    @TableField("monsterid")
    private Integer monsterId;
    @TableField("monstername")
    private String monsterName;
    @TableField("killcount")
    private Integer killCount;
}
