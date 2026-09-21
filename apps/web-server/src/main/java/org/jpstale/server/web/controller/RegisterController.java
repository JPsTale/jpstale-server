package org.jpstale.server.web.controller;

import jakarta.validation.Valid;
import org.jpstale.server.web.dto.RegisterRequest;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.service.RegisterService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户注册 HTTP 接口：创建账号、初始化密码（写入 userdb.user_info）。
 */
@RestController
@RequestMapping("/api/user")
public class RegisterController {

    private final RegisterService registerService;

    public RegisterController(RegisterService registerService) {
        this.registerService = registerService;
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        registerService.register(request.getAccount(), request.getEmail(), request.getPassword());
        return Result.ok();
    }
}
