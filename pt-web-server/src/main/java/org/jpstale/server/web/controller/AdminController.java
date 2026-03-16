package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维管理入口（合并自 pt-admin-server），路径统一为 /api/admin/**。
 * 仅允许角色 admin（user_info.web_admin == true）访问，由 @SaCheckRole 鉴权。
 */
@RestController
@RequestMapping("/api/admin")
@SaCheckRole("admin")
public class AdminController {

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "app", "pt-web-server",
                "description", "运维管理（统一 Web）"
        ));
    }
}
