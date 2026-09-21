package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.jpstale.server.web.dto.AdminMapSummary;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.service.AdminMapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：地图相关 HTTP 接口。
 *
 * 路径统一前缀为 /api/admin/maps，仅 admin 角色可访问（角色来源见 {@link AdminController} 的 javadoc）。
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
     * <p>
     * 方法体内显式再校验一次。历史原因：注解鉴权原先**没有生效**
     * （缺 {@code SaInterceptor}，见 {@code SaTokenConfig}），当时这里是唯一的门。
     * 现在 {@code SaTokenConfig} 已把注解启用，本行是**双保险**（注解 + 显式），不是多余 ——
     * 它同时也是"这个接口必须登录且必须是 admin"这句声明的就近副本。
     */
    @GetMapping
    public Result<List<AdminMapSummary>> list() {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
        List<AdminMapSummary> maps = adminMapService.listAll();
        return Result.ok(maps);
    }

    /**
     * 单张地图详情。
     */
    @GetMapping("/{id}")
    public Result<AdminMapSummary> getById(@PathVariable("id") Integer id) {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
        AdminMapSummary map = adminMapService.findById(id);
        if (map == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(map);
    }
}


