package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import org.jpstale.server.web.clan.ClanService;
import org.jpstale.server.web.clan.dto.*;
import org.jpstale.server.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clan")
public class ClanController {

    private final ClanService clanService;

    public ClanController(ClanService clanService) {
        this.clanService = clanService;
    }

    @PostMapping("/detail.json")
    @SaCheckLogin
    public ApiResponse<ClanDetailResponse> detail(@Valid @RequestBody ClanDetailRequest req) {
        String charName = StpUtil.getSession().getString("accountName");
        ClanDetailResponse resp = clanService.getClanDetail(req.getCharName());
        if (resp == null) return ApiResponse.fail(1, "角色不在公会中");
        return ApiResponse.ok(resp);
    }

    @PostMapping("/members.json")
    @SaCheckLogin
    public ApiResponse<ClanDetailResponse> members(@Valid @RequestBody ClanDetailRequest req) {
        ClanDetailResponse resp = clanService.getClanDetail(req.getCharName());
        if (resp == null) return ApiResponse.fail(1, "角色不在公会中");
        return ApiResponse.ok(resp);
    }

    @PostMapping("/ranking.json")
    @SaCheckLogin
    public ApiResponse<List<ClanRankDto>> ranking() {
        return ApiResponse.ok(clanService.getRanking());
    }

    @PostMapping("/check-name.json")
    @SaCheckLogin
    public ApiResponse<Void> checkName(@Valid @RequestBody ClanNameRequest req) {
        boolean taken = clanService.isClanNameTaken(req.getClanName());
        if (taken) return ApiResponse.fail(2, "公会名已存在");
        return ApiResponse.ok("公会名可用", null);
    }
}
