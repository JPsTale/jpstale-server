package org.jpstale.dao.clandb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 公会主表 `clandb.cl`。
 *
 * <p>⚠ **主键列名与直觉不一致**：活库里它叫 **`idx`**（`bigint`，`nextval('clandb.cl_idx_seq')`），
 * **没有 `id` 列**。本实体保留 Java 名 `id`（调用方 `ClanService` 用 `getId()`），靠
 * `@TableId(value = "idx")` 映射 —— 与 `Ul.clanId → idx`、`CharacterInfo.posX → pos_x` 同一约定：
 * **注解里写活库的真实列名**（活库是混用的：`pos_x` 有下划线，`clanname` 没有，必须逐列核）。
 * 实测依据见 `docs/公会系统-源码分析与客户端接入方案.md` §2.1。
 */
@Data
@TableName(schema = "clandb", value = "cl")
public class Cl {

    @TableId(value = "idx", type = IdType.AUTO)
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
    /*
     * ⚠ 时间列在活库里是 **`timestamp with time zone`**，必须用 `OffsetDateTime` 接。
     * 用 `LocalDateTime` 会在**读结果集时直接抛**
     * `PSQLException: Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime`
     * —— 不是静默 null，是整条查询失败。项目里本来就是这么定的
     * （`CharacterInfo.lastSeenDate` 对 `characterinfo.lastseendate` 同样用 OffsetDateTime）。
     * 全库有 116 个 timestamptz 列，接错一次就抛一次。
     */
    @TableField("regidate")
    private OffsetDateTime regiDate;
    @TableField("limitdate")
    private OffsetDateTime limitDate;
    @TableField("soddate")
    private OffsetDateTime sodDate;
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
