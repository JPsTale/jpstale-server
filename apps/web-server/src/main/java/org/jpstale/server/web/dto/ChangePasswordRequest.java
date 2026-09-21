package org.jpstale.server.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求：前端同样传入 SHA256(UPPERCASE(account)+":"+明文) 的十六进制大写。
 */
@Data
public class ChangePasswordRequest {

    /** 旧密码哈希，64 位十六进制串 */
    @NotBlank(message = "旧密码不能为空")
    @Size(min = 64, max = 64)
    @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "旧密码格式无效")
    private String oldPassword;

    /** 新密码哈希，64 位十六进制串 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 64, max = 64)
    @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "新密码格式无效")
    private String newPassword;
}

