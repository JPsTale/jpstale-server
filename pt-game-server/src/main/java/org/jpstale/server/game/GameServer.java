package org.jpstale.server.game;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.Server;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.service.MovementService;
import org.jpstale.server.game.service.SnapshotPushService;
import org.jpstale.server.game.service.WorldService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 对应原版 C++ 中 Game 侧的 Server 实例。
 * 由 ServerManager 以固定 tick rate 驱动，所有游戏逻辑在此 tick 内完成。
 */
@Slf4j
@Component
public class GameServer implements Server {

    @Autowired
    private MonsterSpawnService monsterSpawnService;

    @Autowired
    private MovementService movementService;

    @Autowired
    private WorldService worldService;

    @Autowired
    private SnapshotPushService snapshotPushService;

    @Override
    public void init() {
        log.info("GameServer init");
    }

    @Override
    public void tick(long currentTimeMillis) {
        monsterSpawnService.tick(currentTimeMillis);
        // 服务端权威玩家移动：每 tick 计算所有移动中玩家的位置
        movementService.tickPlayers();
        // 主动检查玩家是否跨图（依据 PlayerSession 当前位置）
        worldService.tick();
        snapshotPushService.tick();
    }

    @Override
    public void shutdown() {
        log.info("GameServer shutdown");
    }
}