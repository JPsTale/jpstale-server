package org.jpstale.dao.clandb.entity;

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
@TableName(schema = "clandb", value = "clanhome")
public class ClanHome {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("midx")
    private Integer midx;
    @TableField("selserver")
    private Integer selServer;
    @TableField("intro")
    private String intro;
    @TableField("image")
    private String image;
    @TableField("skin")
    private Integer skin;
    @TableField("privhome")
    private Integer privHome;
    @TableField("privmem")
    private Integer privMem;
    @TableField("privboard")
    private Integer privBoard;
}
