package org.jpstale.dao.clandb.entity;

import com.baomidou.mybatisplus.annotation.*;

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
@TableName(schema = "clandb", value = "gamenoticegroup")
public class GameNoticeGroup {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("groupcd")
    private Integer groupCd;
    @TableField("maincd")
    private Integer mainCd;
    @TableField("detailcd")
    private Integer detailCd;
    @TableField("title")
    private String title;
    @TableField("content")
    private String content;
    @TableField("fromday")
    private LocalDateTime fromDay;
    @TableField("today")
    private LocalDateTime toDay;
    @TableField("registday")
    private LocalDateTime registDay;
    @TableField("delactive")
    private Integer delActive;
    @TableField("hit")
    private Integer hit;
    @TableField("flag")
    private Integer flag;
}
