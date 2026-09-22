package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.web.dto.AdminColumn;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.monster.MonsterQueryParams;
import org.jpstale.server.web.service.AdminMonsterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：怪物定义（`gamedb.monsterlist`）接口 —— 列表 / 详情 / 修改 / 刷怪地图 / 掉落表。
 *
 * <p>
 * 与物品管理同一套口径（{@code AdminItemController} + 设计文档）：对外用**数据库列名**；
 * 行含**全部列**；筛选是四组固定条件；分页不走 `Page`（见 {@code AdminEntityService} 的说明）。
 *
 * <p>
 * 鉴权：`@SaCheckRole("admin")` **并且** 方法体内显式再校验一次（注解鉴权曾因缺 `SaInterceptor`
 * 而完全不生效，显式调用是当时唯一的门；现在保留为双保险）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/monsters")
@SaCheckRole("admin")
public class AdminMonsterController {

    private final AdminMonsterService adminMonsterService;

    public AdminMonsterController(AdminMonsterService adminMonsterService) {
        this.adminMonsterService = adminMonsterService;
    }

    /** 列清单：列名 / 类型 / 所属段 / 是否可改 / 是否可筛 / 取值语义。反射生成，不查库。 */
    @GetMapping("/columns")
    public Result<List<AdminColumn>> columns() {
        requireAdmin();
        return Result.ok(adminMonsterService.columns());
    }

    /** 筛选候选项：本性 / 属性（原值 + 计数）+ 有刷怪配置的地图列表。 */
    @GetMapping("/facets")
    public Result<Map<String, Object>> facets() {
        requireAdmin();
        return Result.ok(adminMonsterService.facets());
    }

    /**
     * 列表：四组筛选（名字 / 等级 / 属性+本性 / 所在地图）+ 排序 + 分页，每行全部列。
     *
     * <p>
     * 参数靠 `@RequestParam Map` 收，由 {@link MonsterQueryParams} 按白名单解析 ——
     * **未知键、非整数、区间倒置一律 400**（不静默忽略：静默会把"没生效"伪装成"生效了"）。
     */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam Map<String, String> params) {
        requireAdmin();
        MonsterQueryParams query;
        try {
            query = MonsterQueryParams.parse(params);
        } catch (IllegalArgumentException e) {
            // BusinessException 由 GlobalExceptionHandler 统一落 400，但那条路径**不写日志** ——
            // 所以在这里显式留一条，否则"参数被拒"将无法与"请求没到"区分。
            log.warn("[MonsterAdmin] 列表参数无效：{}（原始参数 {}）", e.getMessage(), params);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        return Result.ok(adminMonsterService.list(query));
    }

    /**
     * 在 `mapmonster` 的 `bossmonster*`/`submonster*` 列里被声明的怪名（列表页 BOSS 徽章用）。
     *
     * <p>
     * ⚠ 路径 `/bosses` 是字面量，Spring 会优先于 `/{id}` 匹配（否则会被当成 id 解析失败）。
     */
    @GetMapping("/bosses")
    public Result<Map<String, Object>> bosses() {
        requireAdmin();
        return Result.ok(adminMonsterService.bosses());
    }

    /** 单行全部列。 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> row = adminMonsterService.getById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.MONSTER_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /**
     * 修改怪物定义：**部分更新**，只改请求体里出现的列；响应为更新后的整行。
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
            row = adminMonsterService.update(id, changes);
        } catch (IllegalArgumentException e) {
            log.warn("[MonsterAdmin] 修改被拒 id={} 原因={} 请求体={}", id, e.getMessage(), changes);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (row == null) {
            throw new BusinessException(ResultCode.MONSTER_NOT_FOUND);
        }
        return Result.ok(row);
    }

    /**
     * 这只怪出现在哪些地图上。
     *
     * <p>
     * `maps` = 会真的刷怪的图（`mapmonster.monster1..12` 里按名字精确匹配）；
     * `bossMaps` = 只在 `bossmonster*`/`submonster*` 列里出现、**当前刷怪代码不消费**的图
     * （界面另行标明，既不忽略也不假装会刷）。
     */
    @GetMapping("/{id}/spawn")
    public Result<Map<String, Object>> spawn(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminMonsterService.spawnMaps(id);
        if (data == null) {
            throw new BusinessException(ResultCode.MONSTER_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * 掉落表：键 = `monsterlist.monsterid`（见 {@code AdminMonsterService} 的类注释）。
     *
     * <p>
     * 每行给原始权重 `chance` 与**算出来的概率** `percent`（权重 / 该 dropid 的权重总和，
     * 用游戏同一份实现计算）；游戏会跳过的行也列出来并标 `skipped`/`skipReason`。
     */
    @GetMapping("/{id}/drops")
    public Result<Map<String, Object>> drops(@PathVariable("id") int id) {
        requireAdmin();
        Map<String, Object> data = adminMonsterService.drops(id);
        if (data == null) {
            throw new BusinessException(ResultCode.MONSTER_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /**
     * **保存掉落表**：请求体 `{"rows":[…]}`，rows 就是保存后的**全部行**（整表替换语义）。
     *
     * <p>
     * 每行：`{id?, items, chance, goldMin?, goldMax?}` —— 带 `id` = 改这一行，不带 = 新增，
     * 现有行未出现在 rows 里 = 删除。校验不过一律 400 且**整表不落地**（一个事务）。
     * 详见 {@code AdminMonsterService.saveDrops} 的注释（含"只会动到这一只怪"的安全性质）。
     */
    @PostMapping("/{id}/drops")
    public Result<Map<String, Object>> saveDrops(@PathVariable("id") int id,
                                                 @RequestBody Map<String, Object> body) {
        requireAdmin();
        Object raw = body == null ? null : body.get("rows");
        if (!(raw instanceof List<?> list)) {
            log.warn("[MonsterAdmin] 保存掉落被拒 id={}：请求体缺少 rows（收到 {}）", id, body);
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                log.warn("[MonsterAdmin] 保存掉落被拒 id={}：rows 里有非对象元素 {}", id, o);
                throw new BusinessException(ResultCode.PARAM_ERROR);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            m.forEach((k, v) -> row.put(String.valueOf(k), v));
            rows.add(row);
        }
        Map<String, Object> data;
        try {
            data = adminMonsterService.saveDrops(id, rows);
        } catch (IllegalArgumentException e) {
            log.warn("[MonsterAdmin] 保存掉落被拒 id={} 原因={}", id, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (data == null) {
            throw new BusinessException(ResultCode.MONSTER_NOT_FOUND);
        }
        return Result.ok(data);
    }

    /** 显式再校验一次（理由见类 javadoc）。 */
    private static void requireAdmin() {
        StpUtil.checkLogin();
        StpUtil.checkRole("admin");
    }
}
