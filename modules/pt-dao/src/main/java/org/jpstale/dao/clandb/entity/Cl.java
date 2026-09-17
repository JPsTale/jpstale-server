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
@TableName(schema = "clandb", value = "cl")
public class Cl {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("clanname")
    private String clanName;
    @TableField("note")
    private String note;
    @TableField("notecnt")
    private Integer noteCnt;
    @TableField("userid")
    private String userId;
    @TableField("clanzang")
    private String clanZang;
    @TableField("flag")
    private Integer flag;
    @TableField("memcnt")
    private Integer memCnt;
    @TableField("miconcnt")
    private Integer mIconCnt;
    @TableField("regidate")
    private LocalDateTime regiDate;
    @TableField("limitdate")
    private LocalDateTime limitDate;
    @TableField("soddate")
    private LocalDateTime sodDate;
    @TableField("delactive")
    private String delActive;
    @TableField("pflag")
    private Integer pFlag;
    @TableField("kflag")
    private Integer kFlag;
    @TableField("cpoint")
    private Integer cPoint;
    @TableField("cwin")
    private Integer cWin;
    @TableField("cfail")
    private Integer cFail;
    @TableField("clanmoney")
    private Long clanMoney;
    @TableField("cnflag")
    private Integer cnFlag;
    @TableField("siegemoney")
    private Long siegeMoney;
}
