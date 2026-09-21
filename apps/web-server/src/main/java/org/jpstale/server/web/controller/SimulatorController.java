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
 */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final SimulatorService simulatorService;

    public SimulatorController(SimulatorService simulatorService) {
        this.simulatorService = simulatorService;
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return simulatorService.categories();
    }

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

    @GetMapping("/mixes")
    public List<Map<String, Object>> mixes(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) String subtype) {
        return simulatorService.mixes(type, subtype);
    }

    @GetMapping("/item/{id}")
    public ResponseEntity<ItemDetail> item(@PathVariable Integer id) {
        ItemDetail detail = simulatorService.detail(id);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }
}