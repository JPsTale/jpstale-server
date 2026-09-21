package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.web.dto.AdminItemColumn;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.item.ItemQueryParams;
import org.jpstale.server.web.service.AdminItemService;
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
 * 管理端：物品定义（`gamedb.itemlist`）接口 —— 列表 / 详情 / 修改 / Mix 配方。
 *
 * <p>
 * 设计见 {@code docs/plans/2026-09-21-pt-web-admin-items-design.md}。要点：
 * 对外用**数据库列名**；行含**全部列**；筛选是四组固定条件；分页不走 `Page`（见 AdminItemService 的说明）。
 *
 * <p>
 * 鉴权：`@SaCheckRole("admin")` **并且** 方法体内显式再校验一次。
 * 那个"并且"不是冗余 —— 注解鉴权此前因缺 `SaInterceptor` 而**完全不生效**（匿名曾返回 200），
 * 显式调用是当时唯一的门；现在 {@code SaTokenConfig} 已启用注解，这里保留为双保险。
 *
 * @see AdminItemService
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/items")
@SaCheckRole("admin")
public class AdminItemController {

    private final AdminItemService adminItemService;

    public AdminItemController(AdminItemService adminItemService) {
        this.adminItemService = adminItemService;
    }

    /** 列清单：列名 / 类型 / 所属段 / 是否可改 / 是否可筛。反射生成，不查库。 */
    @GetMapping("/columns")
    public Result<List<AdminItemColumn>> columns() {
        requireAdmin();
        return Result.ok(adminItemService.columns());
    }

    /**
     * 筛选用的取值清单（原值 + 计数），供前端做候选项。
     *
     * <p>
     * 这是设计文档 5 个端点之外补的一个：`category` 是**区分大小写的精确匹配**，
     * 而库里有 `event`/`Event` 这种同名不同大小写的取值 —— 没有"带计数的原值列表"，
     * 使用者只会搜到一半还不知道（设计文档 §5.2 的那条注意事项要落地就需要它）。
     */
    @GetMapping("/facets")
    public Result<Map<String, Object>> facets() {
        requireAdmin();
        return Result.ok(adminItemService.facets());
    }

    /**
     * 列表：四组筛选 + 排序 + 分页，每行全部列。
     *
     * <p>
     * 参数靠 {@code @RequestParam Map} 收，由 {@link ItemQueryParams} 按白名单解析 ——
     * **未知键、非整数、区间倒置一律 400**（不静默忽略：静默会把"没生效"伪装成"生效了"）。
     * `page`/`size` 越界是**夹紧**，不是报错。
     */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam Map<String, String> params) {
        requireAdmin();
        ItemQueryParams query;
        try {
            query = ItemQueryParams.parse(params);
        } catch (IllegalArgumentException e) {
            // BusinessException 由 GlobalExceptionHandler 统一落 400，但那条路径**不写日志** ——
            // 所以在这里显式留一条，否则"参数被拒"将无法与"请求没到"区分（本项目踩过这个坑）。
            log.warn("[ItemAdmin] 列表参数无效：{}（原始参数 {}）", e.getMessage(), params);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        return Result.ok(adminItemService.list(query));
    }

    /** 单行全部列。 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> row = adminItemService.getById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.ITEM_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /**
     * 修改物品定义：**部分更新**，只改请求体里出现的列；响应为更新后的整行。
     *
     * <p>
     * 校验（不过一律 400，且留日志）：列名必须存在于库、必须可改（主键与时间戳类列除外）、
     * 值类型必须与字段相容。`null` 的语义是"不改"，不是"清空"。
     */
    @PostMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable("id") int id,
                                             @RequestBody Map<String, Object> changes) {
        requireAdmin();
        Map<String, Object> row;
        try {
            row = adminItemService.update(id, changes);
        } catch (IllegalArgumentException e) {
            log.warn("[ItemAdmin] 修改被拒 id={} 原因={} 请求体={}", id, e.getMessage(), changes);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (row == null) {
            throw new BusinessException(ResultCode.ITEM_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /** 该物品可用的 Mix 配方（详情浮层的 Mix 下拉用）。 */
    @GetMapping("/{id}/mixes")
    public Result<List<Map<String, Object>>> mixes(@PathVariable("id") int id) {
        requireAdmin();
        List<Map<String, Object>> recipes = adminItemService.mixes(id);
        if (recipes == null) {
            throw new BusinessException(ResultCode.ITEM_NOT_FOUND);
        }
        return Result.ok(recipes);
    }

    /** 显式再校验一次（理由见类 javadoc）。 */
    private static void requireAdmin() {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
    }
}
