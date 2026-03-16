package org.jpstale.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordResponse {

    private boolean success;
    private String message;

    public static ChangePasswordResponse ok(String message) {
        return new ChangePasswordResponse(true, message);
    }

    public static ChangePasswordResponse fail(String message) {
        return new ChangePasswordResponse(false, message);
    }
}

