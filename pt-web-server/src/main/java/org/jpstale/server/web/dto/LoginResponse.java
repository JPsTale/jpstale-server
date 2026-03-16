package org.jpstale.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private boolean success;
    private String message;
    /** 仅当 success 为 true 时有效：是否为 Web 系统管理员 */
    private Boolean webAdmin;

    public static LoginResponse ok(boolean webAdmin) {
        return new LoginResponse(true, "OK", webAdmin);
    }

    public static LoginResponse fail(String message) {
        return new LoginResponse(false, message, null);
    }
}
