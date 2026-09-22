package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.web.dto.AdminColumn;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.map.MapQueryParams;
import org.jpstale.server.web.service.AdminMapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：地图（`gamedb.maplist`）—— 列表 / 详情（四表汇聚点） / **列编辑** / **刷怪配置编辑**。
 *
 * <p>
 * 详情四段：maplist 字段（`/columns` + `/{id}`，与物品/怪物/NPC 同构）+
 * 刷怪配置 `/{id}/spawn`（可写）+ NPC `/{id}/npcs` + 刷新点 `/{id}/points`（后两段只读，见各自注释）。
 *
 * <p>
 * ⚠ **刷怪配置的改动要等 game-server 重启才生效**（`MapManager` 只在启动时读一次 `mapmonster`）——
 * 与掉落修复同一条规矩。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/maps")
@SaCheckRole("admin")
public class AdminMapController {

    private final AdminMapService adminMapService;

    public AdminMapController(AdminMapService adminMapService) {
        this.adminMapService = adminMapService;
    }

    /**
     * 列表：五组筛选（名字 / 地形类型 / 等级要求 / PvP / 是否有刷怪配置）+ 排序 + 分页，
     * 每行**全部 7 列**（键 = 数据库列名，与其它模块同构）。
     */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam Map<String, String> params) {
        requireAdmin();
        MapQueryParams query;
        try {
            query = MapQueryParams.parse(params);
        } catch (IllegalArgumentException e) {
            log.warn("[MapAdmin] 列表参数无效：{}（原始参数 {}）", e.getMessage(), params);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        return Result.ok(adminMapService.list(query));
    }

    /** 筛选候选项：地形类型（原文 + 计数）。 */
    @GetMapping("/facets")
    public Result<Map<String, Object>> facets() {
        requireAdmin();
        return Result.ok(adminMapService.facets());
    }

    /** 列清单：列名 / 类型 / 所属段 / 是否可改 / 是否可筛 / 取值语义（与物品/怪物/NPC 同一套）。 */
    @GetMapping("/columns")
    public Result<List<AdminColumn>> columns() {
        requireAdmin();
        return Result.ok(adminMapService.columns());
    }

    /** 单张地图的**全部列**（键 = 数据库列名）。 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> row = adminMapService.getById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /** 修改地图定义：部分更新（等级门槛 / 地形类型 / PvP / 名称 / 场景文件…）。 */
    @PostMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable("id") int id,
                                             @RequestBody Map<String, Object> changes) {
        requireAdmin();
        Map<String, Object> row;
        try {
            row = adminMapService.update(id, changes);
        } catch (IllegalArgumentException e) {
            log.warn("[MapAdmin] 修改被拒 id={} 原因={} 请求体={}", id, e.getMessage(), changes);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (row == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /** 刷怪配置（`mapmonster` 一行）：普通怪槽位 + Boss/副怪槽位 + 上限/间隔。 */
    @GetMapping("/{id}/spawn")
    public Result<Map<String, Object>> spawn(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminMapService.spawn(id);
        if (data == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * **保存刷怪配置**：`waves`/`bossWaves` 就是保存后的全部槽位（空槽位写 NULL）。
     *
     * <p>
     * 校验不过一律 400 且**一行都不写**；怪物名认不出 / 数量为 0 不拒绝但回传 `warnings`
     * （`MapManager` 按名字查模板，确实会跳过它们）。详见 {@code AdminMapService.saveSpawn}。
     */
    @PostMapping("/{id}/spawn")
    public Result<Map<String, Object>> saveSpawn(@PathVariable("id") int id,
                                                @RequestBody Map<String, Object> body) {
        requireAdmin();
        Map<String, Object> data;
        try {
            data = adminMapService.saveSpawn(id, body);
        } catch (IllegalArgumentException e) {
            log.warn("[MapAdmin] 保存刷怪配置被拒 id={} 原因={}", id, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (data == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /** 这张图上的 NPC（`mapnpc`，`npcId` 是 **npclist 主键**，供跳转）。只读。 */
    @GetMapping("/{id}/npcs")
    public Result<Map<String, Object>> npcs(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminMapService.npcs(id);
        if (data == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /** 这张图的刷新点（`mapspawnpoint`）。只读。 */
    @GetMapping("/{id}/points")
    public Result<Map<String, Object>> points(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminMapService.points(id);
        if (data == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * **保存 NPC 摆放**：`npcs` 就是保存后的全部摆放（带 `placeId` 改、不带新增、未出现删除）。
     *
     * <p>
     * 可视化编辑器把拖拽/放置的结果攒起来一次提交（用户 2026-09-22 定：攒着点保存）。
     * 校验不过一律 400 且一行不写；详见 {@code AdminMapService.saveNpcs}。
     */
    @PostMapping("/{id}/npcs")
    public Result<Map<String, Object>> saveNpcs(@PathVariable("id") int id,
                                               @RequestBody Map<String, Object> body) {
        requireAdmin();
        Map<String, Object> data;
        try {
            data = adminMapService.saveNpcs(id, body);
        } catch (IllegalArgumentException e) {
            log.warn("[MapAdmin] 保存 NPC 摆放被拒 id={} 原因={}", id, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (data == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * **保存刷新点**：`points` 就是保存后的全部刷新点（整表替换语义，`description` 空白写 NULL）。
     */
    @PostMapping("/{id}/points")
    public Result<Map<String, Object>> savePoints(@PathVariable("id") int id,
                                                 @RequestBody Map<String, Object> body) {
        requireAdmin();
        Map<String, Object> data;
        try {
            data = adminMapService.savePoints(id, body);
        } catch (IllegalArgumentException e) {
            log.warn("[MapAdmin] 保存刷新点被拒 id={} 原因={}", id, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (data == null) {
            throw new BusinessException(ResultCode.MAP_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * 显式再校验一次。历史原因：注解鉴权原先**没有生效**（缺 {@code SaInterceptor}，见 {@code SaTokenConfig}），
     * 当时这里是唯一的门；现在注解已启用，本行是**双保险**。
     */
    private static void requireAdmin() {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
    }
}
