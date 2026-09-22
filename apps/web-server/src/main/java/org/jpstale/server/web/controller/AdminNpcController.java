package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.web.dto.AdminColumn;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.npc.NpcQueryParams;
import org.jpstale.server.web.service.AdminNpcService;
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
 * 管理端：NPC 定义（`gamedb.npclist`）接口 —— 列表 / 详情 / 修改 / 商店清单 / 摆放。
 *
 * <p>
 * 与物品、怪物同一套口径（对外用**数据库列名**；行含全部列；筛选是固定几组；未知键 400；
 * 分页不走 `Page`）。鉴权：`@SaCheckRole("admin")` + 方法体内显式再校验一次。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/npcs")
@SaCheckRole("admin")
public class AdminNpcController {

    private final AdminNpcService adminNpcService;

    public AdminNpcController(AdminNpcService adminNpcService) {
        this.adminNpcService = adminNpcService;
    }

    /** 列清单：列名 / 类型 / 所属段 / 是否可改 / 是否可筛 / 取值语义。反射生成，不查库。 */
    @GetMapping("/columns")
    public Result<List<AdminColumn>> columns() {
        requireAdmin();
        return Result.ok(adminNpcService.columns());
    }

    /** 筛选候选项：有 NPC 的地图（带计数）+ 事件类型（原值 + 计数）。 */
    @GetMapping("/facets")
    public Result<Map<String, Object>> facets() {
        requireAdmin();
        return Result.ok(adminNpcService.facets());
    }

    /** 列表：四组筛选（名字 / 所在地图 / 是否商人 / 事件类型）+ 排序 + 分页，每行全部列。 */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam Map<String, String> params) {
        requireAdmin();
        NpcQueryParams query;
        try {
            query = NpcQueryParams.parse(params);
        } catch (IllegalArgumentException e) {
            log.warn("[NpcAdmin] 列表参数无效：{}（原始参数 {}）", e.getMessage(), params);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        return Result.ok(adminNpcService.list(query));
    }

    /** 单行全部列。 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> row = adminNpcService.getById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.NPC_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /** 修改 NPC 定义：部分更新（与物品/怪物同一套校验：存在 / 可改 / 类型相容）。 */
    @PostMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable("id") int id,
                                             @RequestBody Map<String, Object> changes) {
        requireAdmin();
        Map<String, Object> row;
        try {
            row = adminNpcService.update(id, changes);
        } catch (IllegalArgumentException e) {
            log.warn("[NpcAdmin] 修改被拒 id={} 原因={} 请求体={}", id, e.getMessage(), changes);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (row == null) {
            throw new BusinessException(ResultCode.NPC_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /** 三个商店列的清单（每列一组物品，条目带 `itemId` 主键供跳转）。 */
    @GetMapping("/{id}/shops")
    public Result<Map<String, Object>> shops(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminNpcService.shops(id);
        if (data == null) {
            throw new BusinessException(ResultCode.NPC_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * **保存商店清单**：键 = 数据库列名（`weaponshop`/`defenseshop`/`miscshop`），值 = 物品码数组。
     * 没出现的键不动；给出的键整体替换（顺序即游戏内商店顺序）。校验不过一律 400 且一行都不写。
     */
    @PostMapping("/{id}/shops")
    public Result<Map<String, Object>> saveShops(@PathVariable("id") int id,
                                                @RequestBody Map<String, Object> body) {
        requireAdmin();
        Map<String, Object> data;
        try {
            data = adminNpcService.saveShops(id, body);
        } catch (IllegalArgumentException e) {
            log.warn("[NpcAdmin] 保存商店被拒 id={} 原因={}", id, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (data == null) {
            throw new BusinessException(ResultCode.NPC_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /** 这个 NPC 站在哪几张图、哪些点（`mapnpc`，只读）。 */
    @GetMapping("/{id}/places")
    public Result<Map<String, Object>> places(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminNpcService.places(id);
        if (data == null) {
            throw new BusinessException(ResultCode.NPC_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /** 显式再校验一次（理由见 AdminItemController 的类 javadoc）。 */
    private static void requireAdmin() {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
    }
}
