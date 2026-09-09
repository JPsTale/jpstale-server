package org.jpstale.server.game;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.Server;
import org.jpstale.server.game.service.GroundItemAOI;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.service.MovementService;
import org.jpstale.server.game.service.RegenerationService;
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
    private RegenerationService regenerationService;

    @Autowired
    private GroundItemAOI groundItemAOI;

    @Autowired
    private org.jpstale.server.game.service.PlayerService playerService;

    private long lastPosSaveAt = 0;

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
        // 地面物品 AOI：进场补发 / 走远消失 / 走近出现（与怪物 AOI 同 tick 同口径）
        groundItemAOI.syncSessions();
        // 每 1 秒结算一次 HP/MP/SP 自动回复
        regenerationService.tick(currentTimeMillis);
        // 周期存档在线玩家坐标/朝向（15s），进程重启/崩溃后仍能回下线位置
        if (currentTimeMillis - lastPosSaveAt >= 15000) {
            lastPosSaveAt = currentTimeMillis;
            playerService.persistAllOnlinePositions();
        }
    }

    @Override
    public void shutdown() {
        log.info("GameServer shutdown");
    }
}