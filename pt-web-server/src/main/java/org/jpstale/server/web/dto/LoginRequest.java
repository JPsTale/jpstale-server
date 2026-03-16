package org.jpstale.server.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    @Size(min = 1, max = 32)
    private String account;

    /** 前端传入的 SHA256 十六进制串（64 字符），与注册格式一致 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 64, max = 64)
    @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "密码格式无效")
    private String password;
}
