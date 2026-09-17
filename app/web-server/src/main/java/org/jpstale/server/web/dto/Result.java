package org.jpstale.server.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体：{code, msg, data}。
 *
 * code == 200 表示成功，msg 为空串；失败时 msg 是 translate key（见 ResultError）。
 * 失败响应体的 HTTP 状态码取自 ResultError.getHttpStatus()，由 GlobalExceptionHandler 设置。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    public static final Integer SUCCESS_CODE = 200;

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return of(SUCCESS_CODE, "", data);
    }

    public static <T> Result<T> error(ResultError error) {
        return of(error.getCode(), error.getMsg(), null);
    }

    public static <T> Result<T> error(Integer code, String msg) {
        return of(code, msg, null);
    }

    public static <T> Result<T> of(Integer code, String msg, T data) {
        return new Result<>(code, msg, data);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
