package org.jpstale.server.web.clan.listener;

import org.jpstale.common.mq.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanDissolveListener extends ClanMessageListener {
    public ClanDissolveListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_DISSOLVE; }
    @Override protected void handle(String data) { clanService.handleClanDissolve(data); }
}
