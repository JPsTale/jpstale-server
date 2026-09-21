package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanCreateListener extends ClanMessageListener {
    public ClanCreateListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_CREATE; }
    @Override protected void handle(String data) { clanService.handleClanCreate(data); }
}
