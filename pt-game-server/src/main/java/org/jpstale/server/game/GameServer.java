package org.jpstale.server.game;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.Server;
import org.springframework.stereotype.Component;
/**
 * 对应原版 C++ 中 Game 侧的 Server 实例。
 * 目前仅实现最小 tick，用于验证主循环在跑。
 */
@Slf4j
@Component
public class GameServer implements Server {
    @Override
    public void init() {
        log.info("GameServer init");
    }
    @Override
    public void tick(long currentTimeMillis) {
        // TODO: 后续在这里驱动 ServerWorld / 地图 / 玩家等逻辑
        // 目前可以先留空，必要时加一个低频日志或计数器
    }
    @Override
    public void shutdown() {
        log.info("GameServer shutdown");
    }
}