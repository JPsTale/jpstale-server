package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.item.GroundItemManager;
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
 * 地面物品 AOI：与 {@link MonsterAOI} 同构的双阈值可见性同步。
 *
 * 玩家进入 / 走动 / 掉落物增减时，按 EU 口径向视野内玩家推送
 * S2C_GroundItemAppear / S2C_GroundItemDisappear：
 *  - 进入 CONNECT(1086) → Appear
 *  - 超出 DISCONNECT(1810) → Disappear
 *  - 过期/被拾取的兜底：物品不再存在时把残留可见项清出并通知
 *
 * 拾取不在本类做：必须由玩家「点击该掉落物」经 C2S_PickupItem 触发
 * （原版：点击道具目标 → Chase 走近 → 到范围拾取），绝不自动吸收路过掉落，
 * 否则垃圾会瞬间占满负重且丢地上即被重吸。
 *
 * 线程模型：由 GameServer.tick（主循环 20Hz）调用，与怪物 AOI 同线程。
 */
@Slf4j
@Component
public class GroundItemAOI {

    private static final float CONNECT = AOIManager.VIEW_RANGE;
    private static final float DISCONNECT = AOIManager.VIEW_RANGE_DISCONNECT;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private GroundItemManager groundItems;

    /** 观察者 characterId → 当前可见的地面物品 id 集合（持久化，双阈值升降级） */
    private final ConcurrentHashMap<Long, Set<Long>> visibleByPlayer = new ConcurrentHashMap<>();

    /** 每 tick 由 GameServer.tick() 驱动：同步所有 playing 会话的地面物品可见集 */
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
            reconcile(e, groundItems.listByMap(e.getMapId()));
        }
        // 清理已离线/未 playing 会话的残留可见集
        visibleByPlayer.keySet().removeIf(pid -> !active.contains(pid));
    }

    private void reconcile(PlayerEntity player, List<GroundItemManager.GroundItem> items) {
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
        Set<Long> visible = visibleByPlayer.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());
        double connectSq = (double) CONNECT * CONNECT;
        double disconnectSq = (double) DISCONNECT * DISCONNECT;

        for (GroundItemManager.GroundItem gi : items) {
            long id = gi.id;
            // 非公共掉落（ownerId != 0）仅 owner 可见（对齐 EU SendItemStageUser）
            if (gi.ownerId != 0 && gi.ownerId != pid) {
                if (visible.remove(id)) {
                    session.send(buildDisappear(id));
                }
                continue;
            }
            double dx = sx - gi.x;
            double dz = sz - gi.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > disconnectSq) {
                if (visible.remove(id)) {
                    session.send(buildDisappear(id));
                }
            } else if (distSq <= connectSq) {
                if (visible.add(id)) {
                    session.send(buildAppear(gi));
                }
            }
        }

        // 物品已不存在（过期/被拾取/清场）但仍在本玩家可见集 → 兜底清出并通知
        if (visible.size() > items.size()) {
            Set<Long> current = new HashSet<>(items.size() + 4);
            for (GroundItemManager.GroundItem gi : items) {
                current.add(gi.id);
            }
            java.util.Iterator<Long> it = visible.iterator();
            while (it.hasNext()) {
                long stale = it.next();
                if (!current.contains(stale)) {
                    it.remove();
                    session.send(buildDisappear(stale));
                }
            }
        }
    }

    /** 构造 S2C_GroundItemAppear（含掉落模型码 codeImg1，客户端据此加载 DropItem 模型） */
    private MessageProto.ServerMessage buildAppear(GroundItemManager.GroundItem gi) {
        Integer code = gi.item.getItemCode();
        String name = gi.item.getTemplate() != null && gi.item.getTemplate().getName() != null
            ? gi.item.getTemplate().getName() : "";
        String dorp = gi.item.getTemplate() != null && gi.item.getTemplate().getCodeImg1() != null
            ? gi.item.getTemplate().getCodeImg1() : "";
        return MessageProto.ServerMessage.newBuilder()
            .setGroundItemAppear(MessageProto.S2C_GroundItemAppear.newBuilder()
                .setItem(CommonProto.GroundItemProto.newBuilder()
                    .setGroundItemId(gi.id)
                    .setItemId(code == null ? 0 : code)
                    .setQuantity(gi.item.getCount())
                    .setPosition(CommonProto.Position.newBuilder()
                        .setX((float) gi.x).setY((float) gi.y).setZ((float) gi.z).build())
                    .setOwnerId(gi.ownerId)
                    .setExpireTime(gi.expireAt)
                    .setName(name)
                    .setDorpItem(dorp)
                    .build())
                .build())
            .build();
    }

    private MessageProto.ServerMessage buildDisappear(long groundItemId) {
        return MessageProto.ServerMessage.newBuilder()
            .setGroundItemDisappear(MessageProto.S2C_GroundItemDisappear.newBuilder()
                .setGroundItemId(groundItemId).build())
            .build();
    }
}
