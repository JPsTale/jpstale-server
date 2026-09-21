package org.jpstale.server.web.clan.listener;

import org.jpstale.common.mq.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanTransferLeaderListener extends ClanMessageListener {
    public ClanTransferLeaderListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_TRANSFER_LEADER; }
    @Override protected void handle(String data) { clanService.handleClanTransferLeader(data); }
}
