package org.jpstale.server.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.jpstale.server.web.clan.ClanParamUtil;
import org.jpstale.server.web.clan.ClanResponse;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

/**
 * 公会系统 17 个接口：双路径 /Clan/xxx.asp（客户端兼容）与 /api/clan/xxx（Web）。
 * 参数从 Query + Form 取，.asp 返回原版文本（\r 分隔），/api 返回 JSON。
 */
@RestController
@RequestMapping
public class ClanController {

    private final ClanService clanService;

    public ClanController(ClanService clanService) {
        this.clanService = clanService;
    }

    private static boolean isAspRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.contains(".asp");
    }

    private static ResponseEntity<?> respond(ClanResponse r, HttpServletRequest request) {
        if (isAspRequest(request)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(r.toAspText());
        }
        return ResponseEntity.ok(r);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // --- CheckClanPlayer: /Clan/CheckClanPlayer.asp, /api/clan/check-player
    @RequestMapping(value = { "/Clan/CheckClanPlayer.asp", "/api/clan/check-player" }, method= {GET, POST})
    public ResponseEntity<?> checkClanPlayer(HttpServletRequest req) {
        String clwon = ClanParamUtil.getParam(req, "clwon");
        String gserver = ClanParamUtil.getParam(req, "gserver");
        return respond(clanService.checkClanPlayer(clwon, gserver), req);
    }

    // --- CheckDate
    @RequestMapping(value = { "/Clan/CheckDate.asp", "/api/clan/check-date" }, method= {GET, POST})
    public ResponseEntity<?> checkDate(HttpServletRequest req) {
        String chname = ClanParamUtil.getParam(req, "chname");
        if (ClanParamUtil.hasBlacklist(req, "chname")) return respond(ClanResponse.of(100), req);
        return respond(clanService.checkDate(chname), req);
    }

    // --- CheckUnknown
    @RequestMapping(value = { "/Clan/CheckUnknown.asp", "/api/clan/check-unknown" }, method= {GET, POST})
    public ResponseEntity<?> checkUnknown(HttpServletRequest req) {
        String chname = ClanParamUtil.getParam(req, "chname");
        if (ClanParamUtil.hasBlacklist(req, "chname")) return respond(ClanResponse.of(100), req);
        return respond(clanService.checkUnknown(chname), req);
    }

    // --- CheckClanName
    @RequestMapping(value = { "/Clan/CheckClanName.asp", "/api/clan/check-name" }, method= {GET, POST})
    public ResponseEntity<?> checkClanName(HttpServletRequest req) {
        String clName = ClanParamUtil.getParam(req, "ClName");
        String gserver = ClanParamUtil.getParam(req, "gserver");
        if (ClanParamUtil.hasBlacklist(req, "ClName", "gserver")) return respond(ClanResponse.of(100), req);
        return respond(clanService.checkClanName(clName, gserver), req);
    }

    // --- CheckClanID
    @RequestMapping(value = { "/Clan/CheckClanID.asp", "/api/clan/check-id" }, method= {GET, POST})
    public ResponseEntity<?> checkClanId(HttpServletRequest req) {
        String numStr = ClanParamUtil.getParam(req, "num");
        String gserver = ClanParamUtil.getParam(req, "gserver");
        if (ClanParamUtil.hasBlacklist(req, "num", "gserver")) return respond(ClanResponse.of(100), req);
        Integer num = numStr != null ? parseInt(numStr, -1) : null;
        if (num == null || num < 0) return respond(ClanResponse.of(0), req);
        return respond(clanService.checkClanId(num, gserver), req);
    }

    @RequestMapping(value = { "/Clan/CheckClanLeader.asp", "/api/clan/check-leader" }, method= {GET, POST})
    public ResponseEntity<?> checkClanLeader(HttpServletRequest req) {
        String userid = ClanParamUtil.getParam(req, "userid");
        String gserver = ClanParamUtil.getParam(req, "gserver");
        if (ClanParamUtil.hasBlacklist(req, "userid", "gserver")) return respond(ClanResponse.of(100), req);
        return respond(clanService.checkClanLeader(userid, gserver), req);
    }

    @PostMapping(value = { "/Clan/NewClan.asp", "/api/clan/new" })
    public ResponseEntity<?> newClan(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname", "clName", "chtype", "lv"))
            return respond(ClanResponse.of(100), req);
        String userid = ClanParamUtil.getParam(req, "userid");
        String gserver = ClanParamUtil.getParam(req, "gserver");
        String chname = ClanParamUtil.getParam(req, "chname");
        String clName = ClanParamUtil.getParam(req, "clName");
        int chtype = parseInt(ClanParamUtil.getParam(req, "chtype"), 0);
        int lv = parseInt(ClanParamUtil.getParam(req, "lv"), 0);
        return respond(clanService.newClan(userid, gserver, chname, clName, chtype, lv), req);
    }

    @PostMapping(value = { "/Clan/DeleteClan.asp", "/api/clan/delete" })
    public ResponseEntity<?> deleteClan(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname", "clName"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.deleteClan(
                ClanParamUtil.getParam(req, "userid"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "chname"),
                ClanParamUtil.getParam(req, "clName")), req);
    }

    @PostMapping(value = { "/Clan/LeavePlayer.asp", "/api/clan/leave-player" })
    public ResponseEntity<?> leavePlayer(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname", "clName", "clwon1"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.leavePlayer(
                ClanParamUtil.getParam(req, "userid"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "chname"),
                ClanParamUtil.getParam(req, "clName"),
                ClanParamUtil.getParam(req, "clwon1"),
                ClanParamUtil.getParam(req, "ticket")), req);
    }

    @PostMapping(value = { "/Clan/LeavePlayerSelf.asp", "/api/clan/leave-self" })
    public ResponseEntity<?> leavePlayerSelf(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname", "clName"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.leavePlayerSelf(
                ClanParamUtil.getParam(req, "userid"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "chname"),
                ClanParamUtil.getParam(req, "clName")), req);
    }

    @RequestMapping(value = { "/Clan/ClanMember.asp", "/api/clan/members" }, method= {GET, POST})
    public ResponseEntity<?> clanMember(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.clanMember(
                ClanParamUtil.getParam(req, "userid"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "chname")), req);
    }

    @PostMapping(value = { "/Clan/ChangeLeader.asp", "/api/clan/change-leader" })
    public ResponseEntity<?> changeLeader(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "chname", "gserver", "clName"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.changeLeader(
                ClanParamUtil.getParam(req, "chname"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "clName")), req);
    }

    @PostMapping(value = { "/Clan/SubLeaderRelease.asp", "/api/clan/sub-leader-release" })
    public ResponseEntity<?> subLeaderRelease(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "chname", "gserver"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.subLeaderRelease(
                ClanParamUtil.getParam(req, "chname"),
                ClanParamUtil.getParam(req, "gserver")), req);
    }

    @PostMapping(value = { "/Clan/SubLeaderUpdate.asp", "/api/clan/sub-leader-update" })
    public ResponseEntity<?> subLeaderUpdate(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "chname", "gserver"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.subLeaderUpdate(
                ClanParamUtil.getParam(req, "chname"),
                ClanParamUtil.getParam(req, "gserver")), req);
    }

    @RequestMapping(value = { "/Clan/GetClanMembers.asp", "/api/clan/members" }, method= {GET, POST})
    public ResponseEntity<?> getClanMembers(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname"))
            return respond(ClanResponse.of(100), req);
        return respond(clanService.getClanMembers(
                ClanParamUtil.getParam(req, "userid"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "chname")), req);
    }

    @RequestMapping(value = { "/Clan/SodScore.asp", "/api/clan/sod-score" }, method= {GET, POST})
    public ResponseEntity<?> sodScore(HttpServletRequest req) {
        if (ClanParamUtil.hasMissingOrBlacklist(req, "userid", "gserver", "chname"))
            return respond(ClanResponse.of(100), req);
        String indexStr = ClanParamUtil.getParam(req, "index");
        if (indexStr == null || indexStr.isEmpty()) return respond(ClanResponse.of(104), req);
        Integer index = parseInt(indexStr, -1);
        return respond(clanService.sodScore(
                ClanParamUtil.getParam(req, "userid"),
                ClanParamUtil.getParam(req, "gserver"),
                ClanParamUtil.getParam(req, "chname"),
                index), req);
    }
}
