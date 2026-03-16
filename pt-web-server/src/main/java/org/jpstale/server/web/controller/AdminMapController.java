package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.jpstale.server.web.dto.AdminMapSummary;
import org.jpstale.server.web.service.AdminMapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：地图相关 HTTP 接口。
 *
 * 路径统一前缀为 /api/admin/maps，仅 admin 角色可访问。
 * 当前仅提供只读查询（列表与单条详情），符合「先做只读、再开写操作」的策略。
 */
@RestController
@RequestMapping("/api/admin/maps")
@SaCheckRole("admin")
public class AdminMapController {

    private final AdminMapService adminMapService;

    public AdminMapController(AdminMapService adminMapService) {
        this.adminMapService = adminMapService;
    }

    /**
     * 地图列表（暂不分页，返回全部条目）。
     *
     * 为防止注解失效，这里显式调用 Sa-Token 进行登录与角色校验。
     */
    @GetMapping
    public ResponseEntity<List<AdminMapSummary>> list() {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
        List<AdminMapSummary> maps = adminMapService.listAll();
        return ResponseEntity.ok(maps);
    }

    /**
     * 单张地图详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminMapSummary> getById(@PathVariable("id") Integer id) {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
        AdminMapSummary map = adminMapService.findById(id);
        if (map == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(map);
    }
}


