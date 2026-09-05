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
import org.jpstale.assets.smd.CollisionMesh;
import org.jpstale.server.game.collision.CollisionSystem;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
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

    // ---------- EU 步长语义（对齐客户端 speedLevelToRunStep + pt-visualizer levelToStepPerTick） ----------
    // 客户端 60fps 渲染：step_f = ((cnt*10+250)*coeff>>8)/256 world（走用 ×180 系数）。
    // 服务端 20fps tick：step/tick = step_f × 3（保持与客户端相同的 world/s）。
    // 弃用 PlayerStatCalculator ×256 速度链：其 runSpeed 返回 raw 单位/秒，而碰撞 move 用 world 单位 —— 会放大 256 倍。
    private static final long EU_COEFF_RUN = 460;
    private static final long EU_COEFF_WALK = 180;

    static int moveSpeedOf(int cnt) {
        return cnt * 10 + 250;
    }

    /** 步长（60fps 语义）：((MoveSpeed*coeff)>>8)/256 */
    static double stepOfF(int cnt, long coeff) {
        return ((long) moveSpeedOf(cnt) * coeff >> 8) / 256.0;
    }

    /** 20fps 每 tick 步长 = stepOfF × 3（客户端 60fps → 服务端 20fps） */
    static double stepPerTick(int cnt, long coeff) {
        return stepOfF(cnt, coeff) * 3.0;
    }

    /**
     * 服务端权威玩家移动：每 tick 调用一次。
     * 对处于 WALK/RUN 状态者按 EU 步长推进位置，碰撞受阻则不移动；
     * 每 tick 向视野内玩家（含自己）广播权威位置 → 客户端据此阈值收敛。
     * IDLE 玩家仅在停止瞬间广播一次（anim 切换，让视野内玩家停下），避免持续刷包。
     */
    public void tickPlayers() {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (!session.isPlaying()) continue;

            PlayerMoveState state = session.getMoveState();
            if (state == PlayerMoveState.ATTACK || state == PlayerMoveState.DEAD) continue;

            if (state.isMoving()) {
                Player p = playerService.getPlayer(session);
                if (p == null) continue;

                // 步长：EU 档位 → per tick（跑 ×460 / 走 ×180）；当前档位固定用最高档 25
                final int euCnt = 25;
                double step = state.isRunning()
                    ? stepPerTick(euCnt, EU_COEFF_RUN)
                    : stepPerTick(euCnt, EU_COEFF_WALK);
                double angle = session.getMoveAngle();
                int mapId = session.getCurrentMapId();

                CollisionMesh.MoveResult r = collisionSystem.move(mapId, session.getX(), session.getY(), session.getZ(), angle, step, 11);
                if (!r.collision) {
                    session.setX(r.x);
                    session.setY(r.y);
                    session.setZ(r.z);
                }
                // 更新 AOI（collision 时 session 坐标未变，onPlayerMove 内部自动跳过同格）
                aoiManager.onPlayerMove(session, session.getX(), session.getZ());

                // 广播权威位置 + 动画（含自己）：客户端以 S2C_PlayerMove 做阈值收敛
                broadcastMove(session);
            } else if (session.getLastSyncedAnimState() != 0x0040) {
                // IDLE：停止瞬间广播一次 STAND（即使坐标未变）
                broadcastMove(session);
            }
        }
    }

    /** 广播玩家权威位置 + 动画状态给视野内所有玩家（含自己）。 */
    private void broadcastMove(PlayerSession session) {
        int animState = animStateOf(session.getMoveState());
        int prevAnim = session.getLastSyncedAnimState();
        if (animState != prevAnim) {
            log.info("[MOVE] {} (id={}) anim state {} -> 0x{:04X} pos=({},{})",
                session.getCharacterName(), session.getCharacterId(),
                String.format("0x%04X", prevAnim), animState,
                (float) session.getX(), (float) session.getZ());
        }
        session.setLastSyncedAnimState(animState);

        MessageProto.ServerMessage moveMessage = MessageProto.ServerMessage.newBuilder()
            .setPlayerMove(MessageProto.S2C_PlayerMove.newBuilder()
                .setPlayerId(session.getCharacterId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) session.getX())
                    .setY((float) session.getY())
                    .setZ((float) session.getZ())
                    .build())
                .setAngle((float) session.getMoveAngle())
                .setAnimState(animState)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();

        for (PlayerSession nearbySession : aoiManager.getNearbyPlayers(session.getX(), session.getZ())) {
            nearbySession.send(moveMessage);
        }
    }

    /** moveState → 客户端 STATE 动画值（STAND/WALK/RUN） */
    private static int animStateOf(PlayerMoveState state) {
        return switch (state) {
            case WALK -> 0x0050;
            case RUN -> 0x0060;
            default -> 0x0040;
        };
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
