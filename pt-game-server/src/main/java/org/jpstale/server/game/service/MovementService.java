package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.codec.GameConstants;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerMoveState;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 移动服务
 * 每 tick 由 updateAndCleanup() 调用，根据怪物当前状态执行位置更新。
 *
 * 原版 PT 怪物移动逻辑（exm Character.cpp）：
 * - 通过 GetSin/GetCos 查表 + angle 计算位移方向
 * - step 是固定值（walk=4, run=8 @16tick/s），与 DB Move_Speed 无关
 * - 每 tick 加到 pX/pZ 上
 *
 * 本服务使用简化向量方向（直接朝目标方向），与原版角速度效果等价。
 * step 值按 20tick/s 缩放（×0.8）保持原版实际移动速度。
 */
@Slf4j
@Component
public class MovementService {

    @Autowired
    private AiEngine aiEngine;

    @Autowired
    private MapRegionService mapRegionService;

    @Autowired
    private MapManager mapManager;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private PlayerStatCalculator statCalculator;

    /**
     * 更新怪物位置（每 tick 调用一次）
     */
    public void updateMonster(Monster monster) {
        if (!monster.isAlive()) return;

        AiContext context = aiEngine.getContext(monster.getId());
        if (context == null) return;

        switch (monster.getState()) {
            case CHASE: {
                // 追击：朝玩家位置以 run 速度移动
                PlayerSession target = context.getTargetPlayer();
                if (target == null) break;
                float tx = target.getX();
                float tz = target.getZ();
                moveToward(monster, tx, tz, GameConstants.MONSTER_RUN_STEP);
                break;
            }
            case PATROL: {
                // 巡逻：朝巡逻点以 walk 速度移动
                moveToward(monster, context.getPatrolX(), context.getPatrolZ(),
                    GameConstants.MONSTER_WALK_STEP);
                break;
            }
            case RETURN: {
                // 归位：朝出生点以 walk 速度移动
                moveToward(monster, monster.getSpawnX(), monster.getSpawnZ(),
                    GameConstants.MONSTER_WALK_STEP);
                break;
            }
            default:
                // IDLE / ATTACK / DEAD → 不移动
                break;
        }

        // 更新 Y（地形高度）
        monster.setY(mapRegionService.getHeight(monster.getMapId(), monster.getX(), monster.getZ()));
    }

    /**
     * 服务端权威玩家移动：每 tick 调用一次。
     * 遍历所有在线玩家，对处于 WALK/RUN 状态者按速度+方向更新位置。
     */
    public void tickPlayers() {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (!session.isPlaying()) continue;

            PlayerMoveState state = session.getMoveState();
            if (!state.isMoving()) continue;

            Player p = playerService.getPlayer(session);
            if (p == null) continue;

            // 步进 = 速度(单位/秒) × tick 毫秒 / 1000
            double speed = state.isRunning() ? statCalculator.runSpeed(p) : statCalculator.walkSpeed(p);
            double step = speed * (GameConstants.TICK_MS / 1000.0);

            // 沿方向移动（angle 弧度，0=Z+，与怪物/原版 GetSin/GetCos 一致）
            double dx = Math.sin(session.getMoveAngle()) * step;
            double dz = Math.cos(session.getMoveAngle()) * step;

            float newX = (float) (session.getX() + dx);
            float newZ = (float) (session.getZ() + dz);

            // 校验位置有效（地图边界/地形）
            int mapId = session.getCurrentMapId();
            if (!mapManager.isValidPosition(mapId, newX, newZ)) {
                continue;
            }

            session.setX(newX);
            session.setZ(newZ);
            // Y 权威用地形高度
            session.setY(mapRegionService.getHeight(mapId, newX, newZ));
            // 更新 AOI（怪物刷怪 proximity / 玩家互见）
            aoiManager.onPlayerMove(session, newX, newZ);
        }
    }

    /**
     * 朝目标方向移动 step 步进（向量方向，距离不足时 snap 到目标）
     */
    private void moveToward(Monster monster, float targetX, float targetZ, double step) {
        float dx = targetX - monster.getX();
        float dz = targetZ - monster.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance <= 0.001) return;

        if (distance <= step) {
            monster.setX(targetX);
            monster.setZ(targetZ);
        } else {
            double ratio = step / distance;
            monster.setX((float) (monster.getX() + dx * ratio));
            monster.setZ((float) (monster.getZ() + dz * ratio));
        }

        // 更新面朝角度（atan2(dx, dz)，0=Z+方向，与原版 GetSin/GetCos 一致）
        monster.setAngle(Math.atan2(dx, dz));
    }
}
