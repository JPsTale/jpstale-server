package org.jpstale.dao.logdb.entity;

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
@TableName(schema = "logdb", value = "eventkilllog")
public class EventKillLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("eventid")
    private Integer eventId;
    @TableField("mapid")
    private Integer mapId;
    @TableField("characterid")
    private Integer characterId;
    @TableField("monsterid")
    private Integer monsterId;
    @TableField("monstereffectid")
    private Integer monsterEffectId;
    @TableField("datetime")
    private LocalDateTime dateTime;
}
