package org.jpstale.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;
    private Object page;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, null);
    }

    public static <T> ApiResponse<T> ok(String msg, T data) {
        return new ApiResponse<>(0, msg, data, null);
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null, null);
    }

    public static <T> ApiResponse<T> fail(String msg) {
        return new ApiResponse<>(1, msg, null, null);
    }
}
