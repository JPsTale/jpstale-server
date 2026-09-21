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
