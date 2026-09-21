package org.jpstale.dao.userdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "userdb", value = "userinfo")
public class UserInfo {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("accountname")
    private String accountName;
    @TableField("password")
    private String password;
    /** 注册时间（text 列，历史数据为 SQL Server 遗留格式，故按字符串处理） */
    @TableField("regisday")
    private String regisDay;
    @TableField("flag")
    private Integer flag;
    @TableField("active")
    private Integer active;
    @TableField("activecode")
    private String activeCode;
    @TableField("coins")
    private Integer coins;
    @TableField("email")
    private String email;
    @TableField("gamemastertype")
    private Integer gameMasterType;
    @TableField("gamemasterlevel")
    private Integer gameMasterLevel;
    @TableField("gamemastermacaddress")
    private String gameMasterMacAddress;
    @TableField("coinstraded")
    private Integer coinsTraded;
    @TableField("banstatus")
    private Integer banStatus;
    @TableField("unbandate")
    private OffsetDateTime unbanDate;
    @TableField("ismuted")
    private Integer isMuted;
    @TableField("mutecount")
    private Integer muteCount;
    @TableField("unmutedate")
    private OffsetDateTime unmuteDate;
    /**
     * ⚠ **不是权限判据，也不接数据库**（`exist = false`）。
     *
     * <p>
     * 活库的 `userdb.userinfo` **没有** `web_admin` 这一列（只有仓库里那份 `postgres-init` 的
     * 另一代 schema `user_info` 才有），`UserInfoMapper.selectOneByAccountName` 的 SQL 与 resultMap
     * 也都不含它 ⇒ 本字段读出来**恒为 null**。它曾让 `/api/admin/**` 的权限门**静默失效**若干时间。
     *
     * <p>
     * Web 管理员的判据是原版 GM 两列 `gamemastertype` / `gamemasterlevel`，
     * 唯一实现在 {@code org.jpstale.common.service.account.GameMasterRule}。
     */
    @TableField(exist = false)
    private Boolean webAdmin;
}
