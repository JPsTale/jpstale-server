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
@TableName(schema = "clandb", value = "prbbsfile")
public class PrBbsFile {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("mindex")
    private Integer mindex;
    @TableField("filename")
    private String fileName;
    @TableField("filesize")
    private String fileSize;
    @TableField("regidate")
    private LocalDateTime regiDate;
}
