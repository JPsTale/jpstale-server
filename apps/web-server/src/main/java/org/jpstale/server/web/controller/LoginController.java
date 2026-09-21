package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.server.web.dto.ChangePasswordRequest;
import org.jpstale.server.web.dto.LoginRequest;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.service.ChangePasswordService;
import org.jpstale.server.web.service.LoginService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web 登录/登出：校验账号密码后使用 Sa-Token 登录，Session 写入 accountName、webAdmin，供 @SaCheckRole 等鉴权使用。
 */
@RestController
@RequestMapping("/api/user")
public class LoginController {

    private final LoginService loginService;
    private final ChangePasswordService changePasswordService;

    public LoginController(LoginService loginService, ChangePasswordService changePasswordService) {
        this.loginService = loginService;
        this.changePasswordService = changePasswordService;
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        UserInfo user = loginService.validate(request.getAccount(), request.getPassword());
        if (user == null) {
            // 账号不存在 / 密码错误 / 被封禁 / 未激活 一律同一口径，不泄露账号是否存在
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }
        StpUtil.login(user.getId());
        boolean webAdmin = Boolean.TRUE.equals(user.getWebAdmin());
        StpUtil.getSession().set("accountName", user.getAccountName());
        StpUtil.getSession().set("webAdmin", webAdmin);
        return Result.ok(accountBody(user.getAccountName(), webAdmin));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok();
    }

    /**
     * 修改当前登录用户的密码。
     */
    @PostMapping("/change-password")
    @SaCheckLogin
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        changePasswordService.changePassword(request.getOldPassword(), request.getNewPassword());
        return Result.ok();
    }

    /**
     * 当前登录用户信息：账号名 + 是否管理员。
     */
    @GetMapping("/me")
    @SaCheckLogin
    public Result<Map<String, Object>> me() {
        String accountName = StpUtil.getSession().getString("accountName");
        Boolean webAdmin = StpUtil.getSession().getModel("webAdmin", Boolean.class);
        return Result.ok(accountBody(accountName, Boolean.TRUE.equals(webAdmin)));
    }

    private static Map<String, Object> accountBody(String accountName, boolean webAdmin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountName", accountName != null ? accountName : "");
        body.put("webAdmin", webAdmin);
        return body;
    }
}
