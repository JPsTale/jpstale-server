package org.jpstale.server.web.clan.listener;

import org.jpstale.common.mq.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanSetSubLeaderListener extends ClanMessageListener {
    public ClanSetSubLeaderListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_SET_SUB_LEADER; }
    @Override protected void handle(String data) { clanService.handleClanSetSubLeader(data); }
}
