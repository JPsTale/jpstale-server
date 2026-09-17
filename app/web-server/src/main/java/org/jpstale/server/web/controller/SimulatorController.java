package org.jpstale.server.web.controller;

import org.jpstale.server.web.simulator.ItemDetail;
import org.jpstale.server.web.simulator.ItemInstance;
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
 * - POST /api/simulator/roll 随机骰生成装备实例
 * - POST /api/simulator/forge 锻造
 * - POST /api/simulator/craft 合成
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

    @PostMapping("/roll")
    public ResponseEntity<ItemInstance> roll(@RequestParam int idCode,
                                             @RequestParam(required = false) Long jobCodeMask) {
        ItemInstance instance = simulatorService.roll(idCode, jobCodeMask);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(instance);
    }
}