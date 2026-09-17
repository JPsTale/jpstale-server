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
@TableName(schema = "gamedb", value = "npclist")
public class NpcList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("name")
    private String name;
    @TableField("gamefile")
    private String gameFile;
    @TableField("message1")
    private String message1;
    @TableField("message2")
    private String message2;
    @TableField("message3")
    private String message3;
    @TableField("message4")
    private String message4;
    @TableField("eventtype")
    private Integer eventType;
    @TableField("eventparam")
    private Integer eventParam;
    @TableField("skillquests")
    private Integer skillQuests;
    @TableField("questid")
    private Integer questId;
    @TableField("questtypeid")
    private Integer questTypeId;
    @TableField("questtypesubid")
    private Integer questTypeSubId;
    @TableField("teleportid")
    private Integer teleportId;
    @TableField("weaponshop")
    private String weaponShop;
    @TableField("defenseshop")
    private String defenseShop;
    @TableField("miscshop")
    private String miscShop;
}
