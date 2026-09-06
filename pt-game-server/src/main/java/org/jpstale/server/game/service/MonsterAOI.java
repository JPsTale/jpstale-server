package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪物 AOI：把服务端权威的怪物状态（出现/移动/死亡/消失）推送给视野内玩家。
 *
 * 可见性双阈值与玩家 AOI 一致（EU 语义）：进入 CONNECT(1086) → Appear；
 * 超出 DISCONNECT(1810) → Disappear。所有集合以玩家 characterId 为 key 持久化。
 *
 * 线程模型：syncSessions/broadcastMove 在主循环线程（MonsterSpawnService.tick）调用；
 * onMonsterDeath 可能在 Netty IO 线程（玩家击杀）调用 —— 内部用 CHM 集合，弱一致即可。
 */
@Slf4j
@Component
public class MonsterAOI {

    private static final float CONNECT = AOIManager.VIEW_RANGE;
    private static final float DISCONNECT = AOIManager.VIEW_RANGE_DISCONNECT;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MonsterSpawnService monsterSpawnService;

    /** 观察者 playerId → 当前可见的怪物 id 集合（持久化，双阈值升降级状态） */
    private final ConcurrentHashMap<Long, Set<Long>> visibleByPlayer = new ConcurrentHashMap<>();

    /** 每 tick 由 MonsterSpawnService.tick() 驱动：同步所有 playing 会话的怪物可见集 */
    public void syncSessions() {
        Set<Long> active = ConcurrentHashMap.newKeySet();
        for (PlayerSession session : sessionManager.getAllSessions()) {
            PlayerEntity e = session != null ? session.getEntity() : null;
            if (e == null || !session.isPlaying() || e.getMapId() < 0) {
                continue;
            }
            Long pid = session.getCharacterId();
            if (pid == null) {
                continue;
            }
            active.add(pid);
            List<Monster> monsters = monsterSpawnService.getMonstersByMap(e.getMapId());
            reconcile(e, monsters);
        }
        // 清理已离线/未 playing 会话的残留可见集
        visibleByPlayer.keySet().removeIf(pid -> !active.contains(pid));
    }

    private void reconcile(PlayerEntity player, List<Monster> monsters) {
        Long pid = player.getSession() != null ? player.getSession().getCharacterId() : null;
        if (pid == null) {
            return;
        }
        double sx = player.getX();
        double sz = player.getZ();
        Set<Long> visible = visibleByPlayer.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());
        double connectSq = (double) CONNECT * CONNECT;
        double disconnectSq = (double) DISCONNECT * DISCONNECT;

        for (Monster m : monsters) {
            long mid = m.getId();
            if (!m.isAlive()) {
                // 死亡怪不在可见集（死亡瞬间由 onMonsterDeath 清出并通知），兜底清理
                visible.remove(mid);
                continue;
            }
            double dx = sx - m.getX();
            double dz = sz - m.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > disconnectSq) {
                if (visible.remove(mid)) {
                    sendDisappear(player.getSession(), mid);
                }
            } else if (distSq <= connectSq) {
                if (visible.add(mid)) {
                    sendAppear(player.getSession(), m);
                }
            }
        }
    }

    /** 怪物位移后广播（由 MonsterSpawnService 主循环每步调用）：仅位置/动画变化时下发 */
    public void broadcastMove(Monster m) {
        if (!m.isAlive()) {
            return;
        }
        int anim = animOf(m);
        double mx = m.getX();
        double mz = m.getZ();
        boolean posChanged = Double.isNaN(m.getLastBroadcastX())
            || Math.abs(mx - m.getLastBroadcastX()) > 0.01
            || Math.abs(mz - m.getLastBroadcastZ()) > 0.01;
        if (!posChanged && anim == m.getLastBroadcastAnim()) {
            return;
        }
        m.setLastBroadcastX(mx);
        m.setLastBroadcastZ(mz);
        m.setLastBroadcastAnim(anim);

        MessageProto.ServerMessage moveMsg = MessageProto.ServerMessage.newBuilder()
            .setMonsterMove(MessageProto.S2C_MonsterMove.newBuilder()
                .setMonsterId(m.getId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) m.getX())
                    .setY((float) m.getY())
                    .setZ((float) m.getZ())
                    .build())
                .setAngle((float) m.getAngle())
                .setAnimState(anim)
                .build())
            .build();
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            if (e.getValue().contains(m.getId())) {
                PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                if (s != null) {
                    s.send(moveMsg);
                }
            }
        }
    }

    /** 怪物死亡：通知观察者（击杀者带 exp/gold）并清出可见集（尸体不保留） */
    public void onMonsterDeath(Monster m, long killerId, int exp, int gold) {
        long mid = m.getId();
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            if (e.getValue().remove(mid)) {
                PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                if (s == null) {
                    continue;
                }
                long pid = e.getKey();
                MessageProto.ServerMessage death = MessageProto.ServerMessage.newBuilder()
                    .setMonsterDeath(MessageProto.S2C_MonsterDeath.newBuilder()
                        .setMonsterId(mid)
                        .setKillerId(killerId)
                        .setExp(pid == killerId ? exp : 0)
                        .setGold(pid == killerId ? gold : 0)
                        .build())
                    .build();
                s.send(death);
                s.send(buildDisappear(mid));
            }
        }
    }

    /** 怪物被移除（死亡超时清理 / 无交互清理）：向仍可见的观察者广播 Disappear */
    public void onMonsterRemoved(Monster m) {
        long mid = m.getId();
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            if (e.getValue().remove(mid)) {
                PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                if (s != null) {
                    s.send(buildDisappear(mid));
                }
            }
        }
    }

    private void sendAppear(PlayerSession session, Monster m) {
        MessageProto.S2C_MonsterAppear.Builder appear = MessageProto.S2C_MonsterAppear.newBuilder()
            .setMonsterId(m.getId())
            .setTemplateId(m.getTemplateId())
            .setName(m.getName() != null ? m.getName() : "")
            .setLevel(m.getLevel())
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) m.getX())
                .setY((float) m.getY())
                .setZ((float) m.getZ())
                .build())
            .setHp(Math.max(0, m.getHp()))
            .setMaxHp(m.getMaxHp())
            .setAngle((float) m.getAngle());
        if (m.getModelFile() != null) {
            appear.setModelFile(m.getModelFile());
        }
        session.send(MessageProto.ServerMessage.newBuilder().setMonsterAppear(appear.build()).build());
    }

    private void sendDisappear(PlayerSession session, long monsterId) {
        session.send(buildDisappear(monsterId));
    }

    private MessageProto.ServerMessage buildDisappear(long monsterId) {
        return MessageProto.ServerMessage.newBuilder()
            .setMonsterDisappear(MessageProto.S2C_MonsterDisappear.newBuilder()
                .setMonsterId(monsterId)
                .build())
            .build();
    }

    /** MonsterState → 客户端动画 token（对齐 S2C_MonsterMove.anim_state 注释）
     *  追逐:能跑(RUN 0x60)才发跑,否则走(WALK 0x50)——与 MovementService 速度档同源 */
    public static int animOf(Monster m) {
        MonsterState state = m.getState();
        return switch (state) {
            case CHASE -> m.isCanRun() ? 0x0060 : 0x0050;
            case PATROL, RETURN -> 0x0050;
            case ATTACK -> 0x0100;
            default -> 0x0040;
        };
    }
}
