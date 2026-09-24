package org.jpstale.dao.clandb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 公会成员表 `clandb.ul`。
 *
 * <p>⚠ **`idx` 与 `midx` 含义完全不同，别混**（活库实测 `ul.idx = cl.idx` **1490/1490 行全中、
 * 零孤儿**）：
 * <ul>
 *   <li>{@code idx} = **所属公会的 id**（外键 → `cl.idx`）。它是 `integer NOT NULL` 且
 *       **没有默认值** ⇒ 插入时必须显式给值。本实体映射为 {@link #clanId}
 *       （Java 名表意、注解写真实列名）。</li>
 *   <li>{@code midx} = **这一行自己的 id**（`bigint`，`nextval('clandb.ul_midx_seq')`）⇒ 它才是 `@TableId`。</li>
 * </ul>
 * ⚠ 活库 `ul` **既没有 `id` 列、也没有 `clan_id` 列**（旧 SQL 写的 `ch_name`/`clan_name`/`m_icon_cnt`
 * 之类列名全部失效）。本表列名一律**无下划线**：`chtype`/`chlv`/`clanname`/`permi`/`joindate`/`delactive`。
 */
@Data
@TableName(schema = "clandb", value = "ul")
public class Ul {

    /**
     * 本行自己的 id → 活库列 `midx`（不是 `idx`！见类注释）。
     * 与 `idx`/`clanId` 是**两个不同的列**，别互相替换。
     * 曾另有一个 `midx` 属性指向同一列 —— 已删（一列两属性会让 MP 生成重复列名，且 XML 里
     * `<id column="midx">` 占用该列后那个属性永远为 null）。
     */
    @TableId(value = "midx", type = IdType.AUTO)
    private Integer id;

    /** 所属公会的 id → 活库列 **`idx`**（不是 `id`、也不是 `clan_id`）；见类注释。 */
    @TableField("idx")
    private Integer clanId;
    @TableField("userid")
    private String userId;
    @TableField("chname")
    private String chName;
    @TableField("chtype")
    private Integer chType;
    @TableField("chlv")
    private Integer chLv;
    @TableField("clanname")
    private String clanName;
    @TableField("permi")
    private String permi;
    /** ⚠ 活库列是 `timestamp with time zone` ⇒ 必须 `OffsetDateTime`（`LocalDateTime` 会抛转换异常）。 */
    @TableField("joindate")
    private OffsetDateTime joinDate;
    @TableField("delactive")
    private String delActive;
    @TableField("pflag")
    private Integer pFlag;
    @TableField("kflag")
    private Integer kFlag;
    @TableField("miconcnt")
    private Integer mIconCnt;
}
