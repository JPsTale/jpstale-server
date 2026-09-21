package org.jpstale.dao.clandb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "clandb", value = "gamenoticeprivate")
public class GameNoticePrivate {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("userid")
    private String userId;
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
