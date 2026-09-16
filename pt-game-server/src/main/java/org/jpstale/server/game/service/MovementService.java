package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.codec.GameConstants;
import org.jpstale.server.game.entity.PlayerEntity;
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
import org.jpstale.server.proto.base.S2C_PlayerMove;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 移动服务。
 *
 * 玩家（客户端位置上权威，方向二）：每 tick 由 GameServer.tick() 调 tickPlayers()，
 * 消费客户端上报的位置做限速/防瞬移校验后应用，并走 AOI + 广播。
 *
 * 怪物（服务端权威）：updateMonster() 依状态推进（追击/巡逻/归位），
 * 对齐原版 PT 怪物移动逻辑（exm Character.cpp）：
 * - 通过 GetSin/GetCos 查表 + angle 计算位移方向
 * - step 是固定值（walk=4, run=8 @16tick/s），与 DB Move_Speed 无关
 * - 每 tick 加到 pX/pZ 上
 * 本服务使用简化向量方向（直接朝目标方向），与原版角速度效果等价。
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
    private PlayerStatCalculator playerStatCalculator;

    /** 玩家跑步时长累计（结算窗口内按 RUN 上报的 tick 间距累加 ms），供 RegenerationService 折算耐力消耗。 */
    private final Map<Long, Long> runLastMs = new ConcurrentHashMap<>();
    private final Map<Long, Long> runMsAccum = new ConcurrentHashMap<>();

    /** 取出并清零某玩家本结算窗口的累计跑步毫秒 */
    public long consumeRunMs(long charId) {
        Long v = runMsAccum.remove(charId);
        return v == null ? 0 : v;
    }

    /**
     * 更新怪物位置（每 tick 调用一次）
     */
    public void updateMonster(Monster monster) {
        if (!monster.isAlive()) return;

        AiContext context = aiEngine.getContext(monster.getId());
        if (context == null) return;

        switch (monster.getState()) {
            case CHASE: {
                // 追击：朝目标玩家实体位置移动;速度档与动画同源:能跑才跑,否则走(原版 IQ≥6+run 动画)
                PlayerEntity target = context.getTargetPlayer();
                if (target == null) break;
                double tx = target.getX();
                double tz = target.getZ();
                double step = monster.isCanRun() ? GameConstants.MONSTER_RUN_STEP : GameConstants.MONSTER_WALK_STEP;
                moveToward(monster, tx, tz, step);
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

    // ---------- EU 步长（仅作玩家移动"客户端位置上权威"的限速基准） ----------
    // 客户端 60fps 语义：step_f = ((cnt*10+250)*coeff>>8)/256 world（走用 ×180 系数）。
    // 玩家位置由客户端上报，服务端不再积分；stepOfF×3/50ms 折算成 world/ms 上限用于限速。
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
    private static final double SPEED_TOLERANCE = 1.5; // 50% 容差（网络抖动/客户端碰撞细微差异）
    private static final double SNAP_SLACK = 16.0;      // 绝对 slack（world），容忍停止/转身等小跳跃
    /**
     * 限速的 `dt` 取**两条上报的到达时刻之差**（不是"上次应用发生的时刻"）——见
     * `PlayerSession.lastAppliedReportArrivalMs` 的说明：应用的坐标是客户端几十毫秒前生成的，
     * "应用时刻"会把报告延迟漏掉，一旦有覆盖/丢包还会漏掉更多周期 ⇒ 合法移动被误判超速
     * （用户 2026-09-14 实测：`dist=28.04 > maxDist=21.69（limPerSec=282 dt=51ms）`，
     * 而客户端实测速度 280.6/秒 —— 与上限一致，没有作弊）。
     * 用同一对端点算 `dist` 与 `dt` 之后，**丢一条上报两个都变大**，天然自洽。
     */
    private static final double REPORT_GAP_FALLBACK_MS = 0.0;
    /** 限速拒绝日志的节流（每个会话最多 5 秒一条；会话对象不强引用，随生命周期回收） */
    private final java.util.Map<PlayerSession, Long> speedRejectLogAt =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

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
            // 诊断汇总（每 5 秒一条）：把"收 / 应用 / 拒绝"三个数字一起打出来。
            // 单看"位置没更新"分不清是**没收到**还是**收到被丢** —— 这两个数字对不上就直接定性。
            long lastDiag = session.getMoveDiagAt();
            if (lastDiag == 0) {
                session.setMoveDiagAt(now);
            } else if (now - lastDiag >= 5000) {
                long[] c = session.drainMoveCounters();
                session.setMoveDiagAt(now);
                if (c[0] + c[1] + c[2] > 0) {
                    // 常态下不打（每个移动中的玩家每 5 秒一条太吵）：要排查时把这个 logger 调到 debug
                    log.debug("[Move] {} 5 秒内：收 {} / 应用 {} / 限速拒绝 {}", session.getCharacterName(),
                            c[0], c[1], c[2]);
                }
            }
            // 跑步耐力统计：RUN(mode=2) 连续上报的 tick 间距累加；停跑/换态即重置
            Long cid = session.getCharacterId();
            if (mode == 2 && cid != null) {
                Long last = runLastMs.get(cid);
                if (last != null) runMsAccum.merge(cid, now - last, Long::sum);
                runLastMs.put(cid, now);
            } else if (cid != null) {
                runLastMs.remove(cid);
            }
        }
    }

    /** 应用一条客户端上报的移动（含限速校验）。坐标/状态写入 PlayerEntity。 */
    private void applyClientMove(PlayerSession session, int mode, long nowMs) {
        PlayerEntity entity = session.getEntity();
        if (entity == null) return; // 未进图/未建实体,忽略上报

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

        double dist = Math.hypot(nx - entity.getX(), nz - entity.getZ());

        // 限速：距离 > 最高跑速×Δt×容差+slack → 拒绝（加速/瞬移/穿图）；首条不设限
        long lastArrival = session.getLastAppliedReportArrivalMs();
        long thisArrival = session.getPendingMoveArrivalMs();
        if (lastArrival > 0 && thisArrival >= lastArrival) {
            // **同一对端点**：dist 是这两条上报坐标之差，dt 是它们到达时刻之差
            double dtMs = Math.max(0, thisArrival - lastArrival);
            // 限速基准 = 玩家属性跑步速度（世界/秒）；查不到玩家（异常场景）回退最高档
            double limPerSec = PLAYER_MAX_RUN_PER_MS * 1000.0;
            Player p = playerService.getPlayer(session);
            if (p != null) {
                limPerSec = playerStatCalculator.runSpeed(p);
            }
            double maxDist = limPerSec / 1000.0 * dtMs * SPEED_TOLERANCE + SNAP_SLACK;
            if (dist > maxDist) {
                session.noteMoveRejected();
                // ⚠ **不许静默**：这条 `return` 原本什么都不说，而它的后果是"服务端实体停在旧位置"——
                // 于是**所有按距离裁决的操作都会莫名失败**（用户 2026-09-14 实测：跑过去拾取药水，
                // 服务端按 45.8 判超距，而 4ms 后位置才对上）。玩家会以为"提前上报了拾取动作"，
                // 其实是这里的限速把这些上报**整批丢掉了**。
                // 限速本身保留（防瞬移/穿图），但要把"为什么丢"喊出来：节流到每个会话每 5 秒一条。
                long nowWall = System.currentTimeMillis();
                Long last = speedRejectLogAt.get(session);
                if (last == null || nowWall - last > 5000) {
                    speedRejectLogAt.put(session, nowWall);
                    log.warn("[Move] {} 上报被限速拒绝：dist={} > maxDist={}（limPerSec={} dt={}ms 容差={} slack={}）"
                            + " —— 位置不更新，实体将停在旧坐标，按距离裁决的操作（拾取/商店）会失败",
                        session.getCharacterName(), String.format("%.2f", dist), String.format("%.2f", maxDist),
                        String.format("%.1f", limPerSec), dtMs, SPEED_TOLERANCE, SNAP_SLACK);
                }
                return; // 拒绝：不更新位置（等待其回到合法范围内）
            }
        }

        session.noteMoveApplied();
        if (session.getPendingMoveArrivalMs() > 0) {
            session.setLastAppliedReportArrivalMs(session.getPendingMoveArrivalMs());
        }
        entity.setX(nx);
        entity.setY(ny);
        entity.setZ(nz);
        entity.setAngle(nAngle);
        entity.setMoveState(PlayerMoveState.fromMode(mode));
        session.setLastMoveAcceptedMs(nowMs);

        // 动画状态：客户端提供了非 0 覆盖（掉落 0x70/0x71/0x72）→ 原样广播；否则按 mode 推导
        int anim = session.getPendingMoveAnimState();
        if (anim == 0) anim = animStateOf(entity.getMoveState());

        // 更新 AOI（坐标已在实体上，同格自动跳过）并广播给视野内玩家（含自己）
        aoiManager.onPlayerMove(entity);
        broadcastMove(session, entity, anim);
    }

    /** 广播玩家位置（服务端校验后）+ 动画状态给视野内所有玩家（含自己）。数据读 PlayerEntity。 */
    private void broadcastMove(PlayerSession session, PlayerEntity entity, int animState) {
        int prevAnim = entity.getLastSyncedAnimState();
        if (animState != prevAnim) {
            log.info("[MOVE] {} (id={}) anim state 0x{} -> 0x{} pos=({},{})",
                session.getCharacterName(), session.getCharacterId(),
                String.format("%04X", prevAnim), String.format("%04X", animState),
                (float) entity.getX(), (float) entity.getZ());
        }
        entity.setLastSyncedAnimState(animState);

        ServerMessage moveMessage = ServerMessage.newBuilder()
            .setPlayerMove(S2C_PlayerMove.newBuilder()
                .setPlayerId(session.getCharacterId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) entity.getX())
                    .setY((float) entity.getY())
                    .setZ((float) entity.getZ())
                    .build())
                .setAngle((float) entity.getAngle())
                .setAnimState(animState)
                .setTimestamp(System.currentTimeMillis())
                // 该玩家正播的那一条动画（含变体）：**原样透传**，旁观者据此直接播同一条。
                // 只透传、不解释 —— 服务端不持有任何动画数据（见 docs/chars/语义化动画系统.md）。
                .setAnimIndex(session.getPendingMoveAnimIndex())
                .setAnimClip(session.getPendingMoveAnimClip() == null ? "" : session.getPendingMoveAnimClip())
                .build())
            .build();

        // 广播范围取 DISCONNECT(1600) 而非 CONNECT(1000)：可见集合是**滞回**式的 —— 进入 1000 才
        // Appear，但要走出 1600 才 Disappear。所以 1000~1600 这一圈里的远端**仍在**观察者的可见
        // 集合里，若按 1000 广播，他们收不到位置/动画更新，会停在最后一条 RUN 上"原地跑步"。
        // 规则：**广播范围必须 ≥ 可见边界（= DISCONNECT）**。与 EU 原来取 1810 是同一个理由。
        for (PlayerEntity nearby : aoiManager.getNearbyPlayers(entity.getX(), entity.getZ(), AOIManager.VIEW_RANGE_DISCONNECT)) {
            PlayerSession ns = nearby.getSession();
            if (ns != null) ns.send(moveMessage);
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
