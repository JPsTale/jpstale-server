package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import org.jpstale.server.web.clan.ClanService;
import org.jpstale.server.web.clan.dto.ClanDetailRequest;
import org.jpstale.server.web.clan.dto.ClanDetailResponse;
import org.jpstale.server.web.clan.dto.ClanNameRequest;
import org.jpstale.server.web.clan.dto.ClanRankDto;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public Result<ClanDetailResponse> detail(@Valid @RequestBody ClanDetailRequest req) {
        return Result.ok(requireClanDetail(req.getCharName()));
    }

    @PostMapping("/members.json")
    @SaCheckLogin
    public Result<ClanDetailResponse> members(@Valid @RequestBody ClanDetailRequest req) {
        return Result.ok(requireClanDetail(req.getCharName()));
    }

    @PostMapping("/ranking.json")
    @SaCheckLogin
    public Result<List<ClanRankDto>> ranking() {
        return Result.ok(clanService.getRanking());
    }

    @PostMapping("/check-name.json")
    @SaCheckLogin
    public Result<Void> checkName(@Valid @RequestBody ClanNameRequest req) {
        if (clanService.isClanNameTaken(req.getClanName())) {
            throw new BusinessException(ResultCode.CLAN_NAME_TAKEN);
        }
        // 可用 = 成功（code 200），不再单独回一句"公会名可用"文案
        return Result.ok();
    }

    private ClanDetailResponse requireClanDetail(String charName) {
        ClanDetailResponse resp = clanService.getClanDetail(charName);
        if (resp == null) {
            throw new BusinessException(ResultCode.NOT_IN_CLAN);
        }
        return resp;
    }
}
