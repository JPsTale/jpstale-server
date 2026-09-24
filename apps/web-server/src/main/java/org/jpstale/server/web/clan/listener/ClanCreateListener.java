package org.jpstale.server.web.clan.listener;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.mq.ClanMessageData;
import org.jpstale.common.mq.ClanMessageTypes;
import org.jpstale.common.service.clan.ClanManager;
import org.springframework.stereotype.Component;

/**
 * MQ 入口：建会（GM/后台代人建会；玩家自己的建会走 game-server 的 C2S_ClanCreate）
 * 载荷各字段的含义见 {@link ClanMessageListener} 的契约表（charName = **操作者**，别按名字猜）。
 */
@Slf4j
@Component
public class ClanCreateListener extends ClanMessageListener {

    public ClanCreateListener(ClanManager clanManager) {
        super(clanManager);
    }

    @Override
    public String getType() {
        return ClanMessageTypes.CLAN_CREATE;
    }

    @Override
    protected void handle(String data) {
        ClanMessageData d = parse(data);
        if (d == null) {
            return;
        }
        ClanManager.Created r = clanManager.createClan(d.getClanName(), d.getCharName(), d.getUserId(),
                d.getCharType(), d.getLevel());
        if (!r.ok()) {
            log.warn("[Clan/MQ] 建会被拒 clan={} 原因={}", d.getClanName(), r.result());
        }
    }
}
