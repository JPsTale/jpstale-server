package org.jpstale.server.web.clan.listener;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.mq.CommonMsg;
import org.jpstale.common.mq.RedisMsgListener;
import org.jpstale.server.web.clan.ClanService;

@Slf4j
public abstract class ClanMessageListener implements RedisMsgListener {
    protected final ClanService clanService;

    protected ClanMessageListener(ClanService clanService) {
        this.clanService = clanService;
    }

    @Override
    public void onMessage(CommonMsg message) {
        log.info("Clan message received: type={}", message.getType());
        try {
            handle(message.getData());
        } catch (Exception e) {
            log.error("Error handling clan message: type={}", message.getType(), e);
        }
    }

    protected abstract void handle(String data);
}
