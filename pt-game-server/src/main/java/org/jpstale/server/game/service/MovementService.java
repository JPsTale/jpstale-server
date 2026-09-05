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

    // ======== 客户端位置上权威（方向二）限速参数 ========
    /** 最高档跑 ≈ stepOfF(25,RUN)×3 per 50ms ⇒ world/ms */
    private static final double PLAYER_MAX_RUN_PER_MS = stepOfF(25, EU_COEFF_RUN) * 3.0 / 50.0; // ≈0.2105
    private static final double SPEED_TOLERANCE = 1.3; // 30% 容差（网络抖动/客户端碰撞细微差异）
    private static final double SNAP_SLACK = 3.0;      // 绝对 slack（world），容忍停止/转身等小跳跃

    /**
     * 每 tick（核心 loop）消费客户端上报的移动：
     * 限速/防瞬移校验 → 应用位置 → AOI → 广播。位置只在核心 loop 线程被修改。
     */
    public void tickPlayers() {
        long now = System.currentTimeMillis();
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (!session.isPlaying()) continue;
            int mode = session.getPendingMoveMode();
            if (mode < 0) continue;
            session.setPendingMoveMode(-1);
            applyClientMove(session, mode, now);
        }
    }

    /** 应用一条客户端上报的移动（含限速校验）。 */
    private void applyClientMove(PlayerSession session, int mode, long nowMs) {
        double nx = session.getPendingMoveX();
        double ny = session.getPendingMoveY();
        double nz = session.getPendingMoveZ();
        double nAngle = session.getPendingMoveAngle();
        if (mode < 0 || mode > 2) mode = 0;
        // 有限性校验（防 NaN/Inf 注入）
        if (Double.isNaN(nx) || Double.isInfinite(nx) ||
            Double.isNaN(ny) || Double.isInfinite(ny) ||
            Double.isNaN(nz) || Double.isInfinite(nz) ||
            Double.isNaN(nAngle) || Double.isInfinite(nAngle)) return;

        double dist = Math.hypot(nx - session.getX(), nz - session.getZ());

        // 限速：距离 > 最高跑速×Δt×容差+slack → 拒绝（加速/瞬移/穿图）；首条不设限
        long lastAccepted = session.getLastMoveAcceptedMs();
        if (lastAccepted > 0) {
            double dtMs = Math.max(0, nowMs - lastAccepted);
            double maxDist = PLAYER_MAX_RUN_PER_MS * dtMs * SPEED_TOLERANCE + SNAP_SLACK;
            if (dist > maxDist) {
                return; // 拒绝：不更新位置（等待其回到合法范围内）
            }
        }

        session.setX(nx);
        session.setY(ny);
        session.setZ(nz);
        session.setMoveAngle(nAngle);
        session.setMoveState(PlayerMoveState.fromMode(mode));
        session.setLastMoveAcceptedMs(nowMs);

        // 更新 AOI（同格自动跳过）并广播给视野内玩家（含自己）
        aoiManager.onPlayerMove(session, nx, nz);
        broadcastMove(session);
    }

    /** 广播玩家权威位置 + 动画状态给视野内所有玩家（含自己）。 */
    private void broadcastMove(PlayerSession session) {
        int animState = animStateOf(session.getMoveState());
        int prevAnim = session.getLastSyncedAnimState();
        if (animState != prevAnim) {
            log.info("[MOVE] {} (id={}) anim state 0x{} -> 0x{} pos=({},{})",
                session.getCharacterName(), session.getCharacterId(),
                String.format("%04X", prevAnim), String.format("%04X", animState),
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

        // 广播范围用 DISCONNECT(1810) 而非 CONNECT(1086)：进入 1086~1810 环带的
        // 远端仍处于可见集合（EU 双阈值，出 1810 才 Disappear），若按 1086 广播，
        // 该区间玩家收不到位置/动画更新会停在最后一条 RUN 上"原地跑步"。
        for (PlayerSession nearbySession : aoiManager.getNearbyPlayers(session.getX(), session.getZ(), AOIManager.VIEW_RANGE_DISCONNECT)) {
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
