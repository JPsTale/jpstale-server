package org.jpstale.server.web.clan.listener;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.mq.ClanMessageData;
import org.jpstale.common.mq.ClanMessageTypes;
import org.jpstale.common.service.clan.ClanManager;
import org.springframework.stereotype.Component;

/**
 * MQ 入口：自己退会（会长不可）
 * 载荷各字段的含义见 {@link ClanMessageListener} 的契约表（charName = **操作者**，别按名字猜）。
 */
@Slf4j
@Component
public class ClanLeaveListener extends ClanMessageListener {

    public ClanLeaveListener(ClanManager clanManager) {
        super(clanManager);
    }

    @Override
    public String getType() {
        return ClanMessageTypes.CLAN_LEAVE;
    }

    @Override
    protected void handle(String data) {
        ClanMessageData d = parse(data);
        if (d == null) {
            return;
        }
        report("退会", clanManager.leaveSelf(d.getClanName(), d.getCharName()));
    }
}
