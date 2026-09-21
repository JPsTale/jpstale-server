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
@TableName(schema = "clandb", value = "ct")
public class Ct {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("sno")
    private Integer sNo;
    @TableField("servername")
    private String serverName;
    @TableField("midx")
    private Integer midx;
    @TableField("clanname")
    private String clanName;
    @TableField("clanjang")
    private Integer clanJang;
    @TableField("clanimage")
    private String clanImage;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("gpcode")
    private String gpCode;
    @TableField("logontime")
    private LocalDateTime logonTime;
    @TableField("ip")
    private String ip;
    @TableField("rno")
    private Integer rNo;
    @TableField("flag")
    private Integer flag;
}
