package org.jpstale.server.web.clan;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.clan.ClanManager;
import org.jpstale.dao.clandb.entity.Cl;
import org.jpstale.dao.clandb.entity.Ul;
import org.jpstale.server.web.clan.dto.ClanDetailResponse;
import org.jpstale.server.web.clan.dto.ClanMemberDto;
import org.jpstale.server.web.clan.dto.ClanRankDto;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 公会的 **REST 读接口**业务逻辑（`/api/clan/*.json`）。
 *
 * <h3>这个类只做"装配"，不碰表</h3>
 * 所有数据都来自 {@link ClanManager}（common-service，公会表的唯一实现）。
 * 这样 web-server 与 game-server 走的是**同一份**读写逻辑，不会各写一套。
 *
 * <h3>2026-09-25 下沉后删掉的</h3>
 * 原先这里还有 17 个"原版 ASP 文本格式"方法（`checkClanPlayer` / `checkDate` / `clanMember` /
 * `sodScore` … 返回 {@code Code=1\rCName=...} 那种）以及 8 个 MQ 适配方法。**全部是死代码**：
 * <ul>
 *   <li>逐个 grep：那 17 个方法的**外部调用方 = 0**。它们对应的"`/Clan/xxx.asp` 兼容路径"
 *       在 `ClanController` 里**从未接线**（见方案文档 §2.2）；</li>
 *   <li>随它们一起删掉了 `ClanResponse`（只被这 17 个方法用）与 `ClanParamUtil`（零引用）。</li>
 * </ul>
 * 写操作已下沉到 {@link ClanManager}；MQ 适配在 `listener/ClanMessageListener`。
 */
@Slf4j
@Service
public class ClanService {

    private static final DateTimeFormatter REGI_LIMIT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final ClanManager clanManager;

    public ClanService(ClanManager clanManager) {
        this.clanManager = clanManager;
    }

    /**
     * 公会详情（`detail.json` / `members.json` 共用。
     * ⚠ 两个端点返回的是**同一个对象**，成员列表就在 `members` 字段里 —— 这是既有行为，未改）。
     *
     * @return 不在任何公会时返回 {@code null}（`ClanController` 据此回 `NOT_IN_CLAN`）
     */
    public ClanDetailResponse getClanDetail(String charName) {
        String chname = charName != null ? charName.trim() : "";
        String clanName = clanManager.clanNameOf(chname);
        if (clanName == null) {
            return null;
        }
        Cl cl = clanManager.findByName(clanName);
        if (cl == null) {
            // ul 有行但 cl 已不存在（公会数据半截）——**不静默**：这说明库不一致，值得看一眼
            log.warn("[Clan] {} 的 ul 指向公会 {}，但 cl 里查不到该公会（数据不一致）", chname, clanName);
            return null;
        }

        ClanDetailResponse resp = new ClanDetailResponse();
        resp.setClanId(cl.getId());
        resp.setClanName(clanName);
        resp.setLeader(cl.getClanZang());
        resp.setNote(cl.getNote());
        resp.setMemberCount(cl.getMemCnt());
        resp.setIconId(cl.getMIconCnt());
        resp.setRegiDate(cl.getRegiDate() != null ? cl.getRegiDate().format(REGI_LIMIT_FORMAT) : "");
        resp.setLimitDate(cl.getLimitDate() != null ? cl.getLimitDate().format(REGI_LIMIT_FORMAT) : "");
        resp.setClanMoney(cl.getClanMoney());
        resp.setCPoint(cl.getCPoint());
        resp.setAmLeader(chname.equals(cl.getClanZang()));

        String subChief = clanManager.subLeaderOf(clanName);
        resp.setSubLeader(subChief != null ? subChief : "");
        resp.setAmSubLeader(subChief != null && subChief.equals(chname));

        // 名次：只统计 cPoint > 0 的公会（既有行为）。名次 = 数组下标 + 1，榜单里没有名次字段。
        resp.setRank(0);
        if (cl.getCPoint() != null && cl.getCPoint() > 0) {
            List<Cl> ranks = clanManager.allByCpointDesc();
            for (int i = 0; i < ranks.size(); i++) {
                if (clanName.equals(ranks.get(i).getClanName())) {
                    resp.setRank(i + 1);
                    break;
                }
            }
        }

        List<ClanMemberDto> members = new ArrayList<>();
        for (Ul u : clanManager.membersOf(clanName)) {
            ClanMemberDto md = new ClanMemberDto();
            md.setCharName(u.getChName());
            md.setUserId(u.getUserId());
            md.setCharType(u.getChType());
            md.setCharLevel(u.getChLv());
            md.setPermission(u.getPermi());
            md.setJoinDate(u.getJoinDate() != null ? u.getJoinDate().format(REGI_LIMIT_FORMAT) : "");
            members.add(md);
        }
        resp.setMembers(members);
        return resp;
    }

    /** 排行榜：**只含 `cPoint > 0` 的公会**（既有行为）。条目无名次字段，名次 = 数组下标 + 1。 */
    public List<ClanRankDto> getRanking() {
        List<ClanRankDto> result = new ArrayList<>();
        for (Cl c : clanManager.allByCpointDesc()) {
            if (c.getCPoint() == null || c.getCPoint() <= 0) {
                continue;
            }
            ClanRankDto rank = new ClanRankDto();
            rank.setClanId(c.getId());
            rank.setClanName(c.getClanName());
            rank.setLeader(c.getClanZang());
            rank.setMemberCount(c.getMemCnt());
            rank.setIconId(c.getMIconCnt());
            rank.setCPoint(c.getCPoint());
            rank.setClanMoney(c.getClanMoney());
            result.add(rank);
        }
        return result;
    }

    /** 公会名是否已被占用（与建会**同一句 SQL**，见 `ClanManager.isNameTaken`）。 */
    public boolean isClanNameTaken(String clanName) {
        return clanManager.isNameTaken(clanName);
    }
}
