package org.jpstale.server.web.auth;

/**
 * Sa-Token Session 的键名 —— **写方（登录）与读方（鉴权、/me）共用这一份**。
 *
 * <p>
 * 为什么收成一处：这些键原先在写方与读方**各写一份字面量**。那种重复的失败方式是**静默**的 ——
 * 改了一边忘另一边，编译不报错，症状是"管理员忽然变成普通用户"或"账号名显示为空"。
 */
public final class SessionKeys {

    private SessionKeys() {
    }

    /** 是否 Web 管理员。值由 {@code GameMasterRule} 判定后写入；{@code StpInterfaceImpl} 读它给出 admin 角色。 */
    public static final String WEB_ADMIN = "webAdmin";

    /** 账号名（{@code /api/user/me} 展示用）。 */
    public static final String ACCOUNT_NAME = "accountName";
}
