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
import org.jpstale.server.game.collision.CollisionMesh;
import org.jpstale.server.game.collision.CollisionSystem;
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
    private AOIManager aoiManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private CollisionSystem collisionSystem;

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
                double tx = target.getX();
                double tz = target.getZ();
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
            double angle = session.getMoveAngle();
            int mapId = session.getCurrentMapId();

            CollisionMesh.MoveResult r = collisionSystem.move(mapId, session.getX(), session.getY(), session.getZ(), angle, step, 11);
            if (r.collision) {
                continue; // 被地形阻挡：原地不动
            }

            session.setX(r.x);
            session.setY(r.y);
            session.setZ(r.z);
            // 更新 AOI（怪物刷怪 proximity / 玩家互见）
            aoiManager.onPlayerMove(session, r.x, r.z);
        }
    }

    /**
     * 朝目标方向移动 step 步进（向量方向，距离不足时 snap 到目标）
     */
    private void moveToward(Monster monster, double targetX, double targetZ, double step) {
        double dx = targetX - monster.getX();
        double dz = targetZ - monster.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance <= 0.001) return;

        double effStep = Math.min(step, distance);
        double angle = Math.atan2(dx, dz);
        CollisionMesh.MoveResult r = collisionSystem.move(monster.getMapId(), monster.getX(), monster.getY(), monster.getZ(), angle, effStep, 11);

        monster.setX(r.x);
        monster.setZ(r.z);
        monster.setY(r.y);
        // 更新面朝角度（atan2(dx, dz)，0=Z+方向，与原版 GetSin/GetCos 一致）
        monster.setAngle(angle);
    }
}
