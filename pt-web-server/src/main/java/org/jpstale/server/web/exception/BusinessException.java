package org.jpstale.server.web.exception;

import org.jpstale.server.web.dto.ResultError;

/**
 * 业务失败。Service 抛出，GlobalExceptionHandler 统一转成 Result + 对应 HTTP 状态码。
 */
public class BusinessException extends RuntimeException {

    private final ResultError errorCode;

    public BusinessException(ResultError errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public ResultError getErrorCode() {
        return errorCode;
    }
}
