package org.jpstale.server.web.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.dto.ResultError;
import org.jpstale.server.web.enums.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常 → 统一 Result + 对应 HTTP 状态码。
 *
 * 刻意**不**兜底 Exception：Spring 自己抛的 404（含静态资源缺失，如 favicon）/405 与真正的系统异常
 * 保持 Spring 默认行为。兜底会把每次静态资源 404 都写成一条 error 日志，并且拿不到合适的状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return respond(e.getErrorCode());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        return respond(ResultCode.NOT_LOGIN);
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<Void>> handleNotRole(NotRoleException e) {
        return respond(ResultCode.NO_PERMISSION);
    }

    /** 请求体校验/解析失败：字段细节只写日志，响应统一给 translate key，不把中文散到代码里。 */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数无效: {}", e.getMessage());
        return respond(ResultCode.PARAM_ERROR);
    }

    private static ResponseEntity<Result<Void>> respond(ResultError error) {
        return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error));
    }
}
