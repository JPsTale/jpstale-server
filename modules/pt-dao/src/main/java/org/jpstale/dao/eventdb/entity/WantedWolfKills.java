package org.jpstale.dao.eventdb.entity;

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
@TableName(schema = "eventdb", value = "wantedwolfkills")
public class WantedWolfKills {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("charid")
    private Integer charId;
    @TableField("mapid")
    private Integer mapId;
    @TableField("unixtime")
    private Integer unixTime;
}
