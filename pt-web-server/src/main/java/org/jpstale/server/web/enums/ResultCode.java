package org.jpstale.server.web.enums;

import org.jpstale.server.web.dto.ResultError;

/**
 * Web 接口返回码。
 *
 * 编码规则沿用参考项目 eshop：五位。1xxxx = 用户/业务，2xxxx = 系统；大类步长 100
 * （101xx 账号、102xx 权限、103xx 参数、104xx 资源、105xx 公会）。
 *
 * msg 是 i18n 的 translate key（客户端翻译），不是给人看的中文。
 * httpStatus 与 code 写在一起，避免在 GlobalExceptionHandler 里再维护一份 code → status 映射
 * （同一判定写两份，改一处就会漂）。
 */
public enum ResultCode implements ResultError {

    SUCCESS(200, 200, ""),

    // 账号（101xx）
    LOGIN_FAILED(10101, 401, "error.web.loginFailed"),
    ACCOUNT_DISABLED(10102, 401, "error.web.accountDisabled"),
    OLD_PASSWORD_WRONG(10105, 400, "error.web.oldPasswordWrong"),
    PASSWORD_UNCHANGED(10106, 400, "error.web.passwordUnchanged"),

    // 权限（102xx）
    NOT_LOGIN(10202, 401, "error.web.notLogin"),
    NO_PERMISSION(10203, 403, "error.web.noPermission"),

    // 参数（103xx）
    PARAM_ERROR(10300, 400, "error.web.paramError"),

    // 资源（104xx）
    USER_NOT_FOUND(10404, 404, "error.web.userNotFound"),
    MAP_NOT_FOUND(10405, 404, "error.web.mapNotFound"),
    ACCOUNT_EXISTS(10409, 409, "error.web.accountExists"),
    EMAIL_EXISTS(10410, 409, "error.web.emailExists"),

    // 公会（105xx）
    NOT_IN_CLAN(10501, 404, "error.web.notInClan"),
    CLAN_NAME_TAKEN(10502, 409, "error.web.clanNameTaken");

    private final Integer code;
    private final int httpStatus;
    private final String msg;

    ResultCode(Integer code, int httpStatus, String msg) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.msg = msg;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public int getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
