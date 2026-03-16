package org.jpstale.server.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.server.web.dto.LoginRequest;
import org.jpstale.server.web.dto.LoginResponse;
import org.jpstale.server.web.service.LoginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 登录/登出：校验账号密码后使用 Sa-Token 登录，Session 写入 accountName、webAdmin，供 @SaCheckRole 等鉴权使用。
 */
@RestController
@RequestMapping("/api/user")
public class LoginController {

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        UserInfo user = loginService.validate(request.getAccount(), request.getPassword());
        if (user == null) {
            return ResponseEntity.badRequest().body(LoginResponse.fail("账号或密码错误"));
        }
        StpUtil.login(user.getId());
        StpUtil.getSession().set("accountName", user.getAccountName());
        StpUtil.getSession().set("webAdmin", Boolean.TRUE.equals(user.getWebAdmin()));
        return ResponseEntity.ok(LoginResponse.ok(Boolean.TRUE.equals(user.getWebAdmin())));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        StpUtil.logout();
        return ResponseEntity.ok().build();
    }
}
