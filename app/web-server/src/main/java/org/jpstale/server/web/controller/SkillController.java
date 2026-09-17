package org.jpstale.server.web.controller;

import org.jpstale.server.web.simulator.SkillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 技能数据接口（pviewer 纸娃娃用）。
 * <p>
 * - GET /api/simulator/skills?job=1 职业技能列表
 */
@RestController
@RequestMapping("/api/simulator")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills(@RequestParam int job) {
        return skillService.skills(job);
    }
}
