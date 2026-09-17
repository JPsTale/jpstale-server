package org.jpstale.server.web.dto;

/**
 * Web 接口错误码契约。
 *
 * ResultCode 用枚举实现它、Result 与 BusinessException 只依赖它，
 * 于是 GlobalExceptionHandler 只认这三个方法，不必知道具体是哪个枚举。
 */
public interface ResultError {

    /** 业务返回码（五位，见 ResultCode 的编码规则）。 */
    Integer getCode();

    /** i18n 的 translate key（如 error.web.loginFailed），由客户端负责翻译。 */
    String getMsg();

    /** 与 code 一一对应的 HTTP 状态码。 */
    int getHttpStatus();
}
