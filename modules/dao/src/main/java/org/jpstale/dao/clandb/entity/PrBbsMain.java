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
@TableName(schema = "clandb", value = "prbbsmain")
public class PrBbsMain {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("pmno")
    private String pmNo;
    @TableField("userid")
    private String userId;
    @TableField("title")
    private String title;
    @TableField("content")
    private String content;
    @TableField("username")
    private String userName;
    @TableField("clanidx")
    private Integer clanIdx;
    @TableField("selserver")
    private Integer selServer;
    @TableField("regidate")
    private LocalDateTime regiDate;
    @TableField("regiip")
    private String regiIp;
    @TableField("hit")
    private Integer hit;
    @TableField("countcom")
    private Integer countCom;
}
