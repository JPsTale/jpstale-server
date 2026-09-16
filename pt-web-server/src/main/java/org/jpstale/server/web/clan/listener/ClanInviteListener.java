package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanInviteListener extends ClanMessageListener {
    public ClanInviteListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_INVITE; }
    @Override protected void handle(String data) { clanService.handleClanInvite(data); }
}
