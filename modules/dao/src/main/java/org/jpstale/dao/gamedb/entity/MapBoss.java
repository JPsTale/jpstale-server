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
@TableName(schema = "gamedb", value = "mapboss")
public class MapBoss {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("mapid")
    private Integer mapId;
    @TableField("bossmonsterid")
    private Integer bossMonsterId;
    @TableField("minionmonsterid")
    private Integer minionMonsterId;
    @TableField("minioncount")
    private Integer minionCount;
}
