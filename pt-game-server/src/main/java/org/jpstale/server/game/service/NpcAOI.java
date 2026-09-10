package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Npc;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC AOI：与 {@link MonsterAOI}/{@link GroundItemAOI} 同构的双阈值可见性同步。
 *
 * 玩家进入 / 走动 / 换图时，按 EU 口径向视野内玩家推送 S2C_NpcAppear / S2C_NpcDisappear：
 *  - 进入 CONNECT(1086) → Appear
 *  - 超出 DISCONNECT(1810) → Disappear
 * NPC 静态不移动，但玩家走动/换图会改变相对距离，故仍走双阈值升降级。
 *
 * 线程模型：由 GameServer.tick（主循环 20Hz）调用，与怪物/物品 AOI 同线程。
 */
@Slf4j
@Component
public class NpcAOI {

    private static final float CONNECT = AOIManager.VIEW_RANGE;
    private static final float DISCONNECT = AOIManager.VIEW_RANGE_DISCONNECT;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private NpcSpawnService npcSpawnService;

    /** 观察者 characterId → 当前可见的 NPC id 集合（持久化，双阈值升降级） */
    private final ConcurrentHashMap<Long, Set<Integer>> visibleByPlayer = new ConcurrentHashMap<>();

    /** 每 tick 由 GameServer.tick() 驱动：同步所有 playing 会话的 NPC 可见集 */
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
            reconcile(e, npcSpawnService.getNpcsByMap(e.getMapId()));
        }
        // 清理已离线/未 playing 会话的残留可见集
        visibleByPlayer.keySet().removeIf(pid -> !active.contains(pid));
    }

    private void reconcile(PlayerEntity player, List<Npc> npcs) {
        PlayerSession session = player.getSession();
        if (session == null) {
            return;
        }
        Long pid = session.getCharacterId();
        if (pid == null) {
            return;
        }
        double sx = player.getX();
        double sz = player.getZ();
        Set<Integer> visible = visibleByPlayer.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());
        double connectSq = (double) CONNECT * CONNECT;
        double disconnectSq = (double) DISCONNECT * DISCONNECT;

        for (Npc npc : npcs) {
            int id = npc.getNpcId();
            double dx = sx - npc.getX();
            double dz = sz - npc.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > disconnectSq) {
                if (visible.remove(id)) {
                    session.send(buildDisappear(id));
                }
            } else if (distSq <= connectSq) {
                if (visible.add(id)) {
                    session.send(buildAppear(npc));
                }
            }
        }

        // 换图兜底：旧图 NPC 已不在当前图列表 → 清出可见集并通知消失
        if (visible.size() > npcs.size()) {
            Set<Integer> current = new HashSet<>(npcs.size() + 4);
            for (Npc npc : npcs) {
                current.add(npc.getNpcId());
            }
            Iterator<Integer> it = visible.iterator();
            while (it.hasNext()) {
                int stale = it.next();
                if (!current.contains(stale)) {
                    it.remove();
                    session.send(buildDisappear(stale));
                }
            }
        }
    }

    private MessageProto.ServerMessage buildAppear(Npc npc) {
        MessageProto.S2C_NpcAppear.Builder b = MessageProto.S2C_NpcAppear.newBuilder()
            .setNpcId(npc.getNpcId())
            .setNameKey(npc.getNameKey() != null ? npc.getNameKey() : "")
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) npc.getX())
                .setY((float) npc.getY())
                .setZ((float) npc.getZ())
                .build())
            .setAngle((float) npc.getAngle());
        if (npc.getModelFile() != null) {
            b.setModelFile(npc.getModelFile());
        }
        return MessageProto.ServerMessage.newBuilder().setNpcAppear(b.build()).build();
    }

    private MessageProto.ServerMessage buildDisappear(int npcId) {
        return MessageProto.ServerMessage.newBuilder()
            .setNpcDisappear(MessageProto.S2C_NpcDisappear.newBuilder()
                .setNpcId(npcId)
                .build())
            .build();
    }
}
