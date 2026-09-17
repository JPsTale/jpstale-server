package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.enums.item.ItemTimerType;
import org.springframework.stereotype.Service;

/**
 * 限时道具（premium）查询接口 —— 本轮 stub。
 * 完整 premium 系统（物品使用 / characteritemtimer 读写 / 登录同步 / 每秒递减 / 落库）另立 spec。
 */
@Slf4j
@Service
public class PremiumService {

    /** 剩余有效秒数；无该 premium 返回 0。 */
    public int getTimeLeft(long playerId, ItemTimerType type) {
        return 0; // TODO(premium-spec): 读 userdb.characteritemtimer
    }
}
