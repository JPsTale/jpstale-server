package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanReleaseSubLeaderListener extends ClanMessageListener {
    public ClanReleaseSubLeaderListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_RELEASE_SUB_LEADER; }
    @Override protected void handle(String data) { clanService.handleClanReleaseSubLeader(data); }
}
