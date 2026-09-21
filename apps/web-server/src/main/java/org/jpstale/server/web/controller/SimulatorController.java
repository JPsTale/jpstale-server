package org.jpstale.server.web.controller;

import org.jpstale.server.web.simulator.ItemDetail;
import org.jpstale.server.web.simulator.SimulatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 装备模拟器接口（Web 用）。
 * <p>
 * - GET /api/simulator/categories 分类列表
 * - GET /api/simulator/items 分类分页列表
 * - GET /api/simulator/item/{id} 物品详情
 * - GET /api/simulator/mixes 合成配方
 * <p>
 * ⚠ 原先的 {@code POST /api/simulator/roll}（随机骰生成装备实例）**已删除**：
 * 它的实现与游戏内那份并行且已经分叉（模拟器显示的值与游戏里掷出来的不一致）。
 * 掷点的唯一实现在 common-service 的
 * {@link org.jpstale.common.service.item.ItemRollService}；接口待重新设计时直接调它。
 *
 * @deprecated 模拟器页面 {@code static/simulator/index.html} 已于 2026-09-21 删除，
 * 本类随之废弃。物品的查询与维护改由 {@code /api/admin/items/**} 承担
 * （设计见 {@code docs/plans/2026-09-21-pt-web-admin-items-design.md}）。
 * <p>
 * ⚠ **要删本类之前先读这一段**：{@code GET /items} 不是死接口 ——
 * 纸娃娃 {@code static/pviewer/index.html} 仍在调它（该文件第 993、1096 行），
 * 并读返回的 {@code items[].dorpItem} / {@code items[].reqLevel} 做过滤与排序。
 * {@code /items} 又依赖 {@link SimulatorService#list} 与
 * {@link org.jpstale.server.web.simulator.ItemCategory}。
 * 没有替代接口之前删掉它们，坏的不是模拟器，是 pviewer。
 * <p>
 * 本类的四个端点里，{@code /categories}、{@code /mixes}、{@code /item/{id}}
 * 已无任何调用方（可先删）；{@code /items} 有活消费者（见上）。
 * {@code /api/simulator/skills} 由另一个类 {@link SkillController} 提供，
 * 同被 pviewer 使用，与本类的废弃无关。
 */
@Deprecated
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final SimulatorService simulatorService;

    public SimulatorController(SimulatorService simulatorService) {
        this.simulatorService = simulatorService;
    }

    /**
     * @deprecated 已无调用方（原使用者是已删除的模拟器页面）。
     */
    @Deprecated
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return simulatorService.categories();
    }

    /**
     * ⚠ 仍在使用：纸娃娃 {@code static/pviewer/index.html:993,1096}。
     *
     * @deprecated 模拟器已废弃，但本端点有 pviewer 这个活消费者 ——
     * 替代接口就绪前不要删。
     */
    @Deprecated
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(required = false) String type,
                                     @RequestParam(required = false) String subtype,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        // 兼容旧参数 category（分类名 = 子类名），wartale 用 type+subtype
        if (subtype == null && category != null) {
            subtype = category;
        }
        return simulatorService.list(type, subtype, page, size);
    }

    /**
     * @deprecated 已无调用方（原使用者是已删除的模拟器页面）。
     * 管理端的 Mix 下拉改由 {@code GET /api/admin/items/{id}/mixes} 提供。
     */
    @Deprecated
    @GetMapping("/mixes")
    public List<Map<String, Object>> mixes(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) String subtype) {
        return simulatorService.mixes(type, subtype);
    }

    /**
     * @deprecated 已无调用方（原使用者是已删除的模拟器页面）。
     * 管理端改用 {@code GET /api/admin/items/{id}}，且返回的是数据库全部列，
     * 不是这里的 {@link ItemDetail}（后者把 DB 的 {@code category} 列覆盖成了 idcode 派生的分类）。
     */
    @Deprecated
    @GetMapping("/item/{id}")
    public ResponseEntity<ItemDetail> item(@PathVariable Integer id) {
        ItemDetail detail = simulatorService.detail(id);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }
}
