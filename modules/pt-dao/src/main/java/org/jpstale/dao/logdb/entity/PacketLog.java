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
@TableName(schema = "logdb", value = "packetlog")
public class PacketLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("servertype")
    private Integer serverType;
    @TableField("packetid")
    private Integer packetId;
    @TableField("totalcount")
    private Integer totalCount;
    @TableField("totaldurationms")
    private Long totalDurationMs;
    @TableField("mindurationms")
    private Integer minDurationMs;
    @TableField("maxdurationms")
    private Integer maxDurationMs;
    @TableField("datetime")
    private LocalDateTime dateTime;
}
