package org.jpstale.server.game;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.Server;
import org.jpstale.server.game.item.GroundItemManager;
import org.jpstale.server.game.service.GroundItemAOI;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.service.MovementService;
import org.jpstale.server.game.service.NpcAOI;
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
    private org.jpstale.server.game.service.CombatService combatService;

    @Autowired
    private GroundItemAOI groundItemAOI;

    /** 地面物：过期清扫（唯一移除点，见 {@link GroundItemManager#expireSweep()}） */
    @Autowired
    private GroundItemManager groundItemManager;

    @Autowired
    private NpcAOI npcAOI;

    @Autowired
    private org.jpstale.server.game.service.PlayerService playerService;

    @Autowired
    private org.jpstale.server.game.network.SessionManager sessionManager;

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
        // 死亡躺下的玩家：躺满 1 分钟强制送回村庄（原版"一段时间后强制复活"）
        combatService.tickDeaths(currentTimeMillis);
        // 地面物过期清扫：**唯一**的过期移除点（查询不再就地删过期项）。
        // 必须在 AOI 之前：移除会被登记，紧接着 syncSessions 取走并给观察者发 Disappear。
        groundItemManager.expireSweep();
        // 地面物品 AOI：进场补发 / 走远消失 / 走近出现（与怪物 AOI 同 tick 同口径）
        groundItemAOI.syncSessions();
        // NPC AOI：玩家进图/换图时下发该图所有 NPC
        npcAOI.syncSessions();
        // 每 1 秒结算一次 HP/MP/SP 自动回复
        regenerationService.tick(currentTimeMillis);
        // 周期存档在线玩家坐标/朝向（15s），进程重启/崩溃后仍能回下线位置
        if (currentTimeMillis - lastPosSaveAt >= 15000) {
            lastPosSaveAt = currentTimeMillis;
            playerService.persistAllOnlinePositions();
        }
        // ===== 合批发送（必须放在 tick 的**最后**）=====
        // 上面各步产生的消息这一 tick 内只入队（PlayerSession.send），到这里一次性发出：
        // 同一 tick 发给同一玩家的多条 → 一个 S2C_Batch → 一次编码、一次 writeAndFlush。
        // 放在最后是硬要求：放在中间会让"这一步之后产生的消息"等到下一 tick 才发（多 50ms 延迟）。
        sessionManager.flushAll();
    }

    @Override
    public void shutdown() {
        log.info("GameServer shutdown");
    }
}