package org.jpstale.server.common.model;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.Data;

import java.time.Instant;

/**
 * Java 版 UserData，会话级运行时状态。
 *
 * 对齐 C++ Server/server/userdataserver.h 里的 UserData 主要字段（简化版）：
 * - 账号名 / 账号ID
 * - GM 等级
 * - Ticket
 * - 静音状态
 * - 登录时间、IP 等
 *
 * 注意：这不是 DAO 层的 UserInfo（数据库实体），而是运行时挂在 Netty Channel 上的状态。
 */
@Data
public class UserData {

    /**
     * Netty Channel 上存放 UserData 的 AttributeKey。
     *
     * 使用方法：
     *   UserData ud = UserData.get(ctx.channel());
     *   if (ud == null) { ud = UserData.create(ctx.channel()); }
     */
    public static final AttributeKey<UserData> ATTR_KEY =
            AttributeKey.valueOf("ptUserData");

    /**
     * 从 Channel 读取 UserData，没有则返回 null。
     */
    public static UserData get(Channel ch) {
        return ch.attr(ATTR_KEY).get();
    }

    /**
     * 在 Channel 上创建并绑定一个新的 UserData。
     */
    public static UserData create(Channel ch) {
        UserData ud = new UserData();
        ud.setLoginTime(Instant.now().toEpochMilli());
        ch.attr(ATTR_KEY).set(ud);
        return ud;
    }

    // ============ 运行时状态字段 ============

    /**
     * 账号名（C++: pcUserData->szAccountName）
     */
    private String accountName;

    /**
     * 账号ID（C++: pcUser->iAccountID）
     */
    private Integer accountId;

    /**
     * 当前登录的 Ticket（C++: pcUserData->iTicket）
     */
    private Integer ticket;

    /**
     * GM 等级（C++: pcUser->SetGameLevel / pcUserData->iGameLevel）
     */
    private Integer gmLevel;

    /**
     * 是否被禁言（C++: pcUser->bMuted）
     */
    private boolean muted;

    /**
     * 禁言到期时间（毫秒时间戳，对应 C++ dwUnMuteExpiryTime）
     */
    private Long unmuteExpiry;

    /**
     * 客户端 IP
     */
    private String clientIp;

    /**
     * 登录时间（毫秒时间戳）
     */
    private Long loginTime;
}