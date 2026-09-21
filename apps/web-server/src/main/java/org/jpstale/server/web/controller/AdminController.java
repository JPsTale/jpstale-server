package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import org.jpstale.server.web.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维管理入口（合并自 pt-admin-server），路径统一为 /api/admin/**。
 *
 * <p>
 * 仅允许角色 admin 访问，由 {@code @SaCheckRole("admin")} 鉴权。
 * 角色由 {@link org.jpstale.server.web.auth.StpInterfaceImpl} 按登录时写入 Session 的
 * {@code webAdmin} 判定，而 {@code webAdmin} 的来源是**原版 GM 两列**
 * （{@code userinfo.gamemastertype} / {@code gamemasterlevel}，唯一实现
 * {@link org.jpstale.common.service.account.GameMasterRule}）——
 * **不是** {@code user_info.web_admin}（活库没那一列，见设计文档 §5.0）。
 *
 * <p>
 * ⚠ 注解鉴权依赖 {@code SaTokenConfig} 注册的 {@code SaInterceptor}；没有它，本注解**不拦任何请求**
 * （实测过：匿名调本接口曾返回 200）。
 */
@RestController
@RequestMapping("/api/admin")
@SaCheckRole("admin")
public class AdminController {

    @GetMapping("/info")
    public Result<Map<String, String>> info() {
        return Result.ok(Map.of(
                "app", "pt-web-server",
                "description", "运维管理（统一 Web）"
        ));
    }
}
