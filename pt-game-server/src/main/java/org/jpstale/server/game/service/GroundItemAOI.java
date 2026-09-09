package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.item.GroundItemManager;
import org.jpstale.server.game.item.ItemService;
import org.jpstale.server.game.model.Player;
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
 * 拾取对齐原版 STG 语义：原版 = 点击物品→角色走到掉落物旁→服务端按坐标结算。
 * 这里在玩家走进拾取半径（PICK_REACH）时按 tick 自动结算（等效原版"走到旁即拾"），
 * 手动 C2S_PickupItem 仍在触达范围即时生效（双通道，带每玩家冷却防连拾）。
 *
 * 线程模型：由 GameServer.tick（主循环 20Hz）调用，与怪物 AOI 同线程。
 */
@Slf4j
@Component
public class GroundItemAOI {

    private static final float CONNECT = AOIManager.VIEW_RANGE;
    private static final float DISCONNECT = AOIManager.VIEW_RANGE_DISCONNECT;
    /** 拾取触达半径（世界单位，原版玩家走到掉落物旁即结算） */
    private static final double PICK_REACH = 2.0d;
    /** 自动拾取每玩家最小间隔（防止堆叠金币一帧全吸/误触连续拾取） */
    private static final long PICK_COOLDOWN_MS = 700;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private GroundItemManager groundItems;

    @Autowired
    private ItemService itemService;

    /** 观察者 characterId → 当前可见的地面物品 id 集合（持久化，双阈值升降级） */
    private final ConcurrentHashMap<Long, Set<Long>> visibleByPlayer = new ConcurrentHashMap<>();
    /** 观察者 characterId → 上次自动拾取时间戳（冷却） */
    private final ConcurrentHashMap<Long, Long> lastPickByPlayer = new ConcurrentHashMap<>();

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
            List<GroundItemManager.GroundItem> items = groundItems.listByMap(e.getMapId());
            reconcile(e, items);
            tryAutoPick(e, session, items, System.currentTimeMillis());
        }
        // 清理已离线/未 playing 会话的残留可见集与冷却
        visibleByPlayer.keySet().removeIf(pid -> !active.contains(pid));
        lastPickByPlayer.keySet().removeIf(pid -> !active.contains(pid));
    }

    /** 走进拾取半径即自动结算入包（对齐原版：走到掉落物旁即拾取） */
    private void tryAutoPick(PlayerEntity player, PlayerSession session, List<GroundItemManager.GroundItem> items, long now) {
        Player p = player.getPlayer();
        if (p == null) {
            return;
        }
        Long pid = session.getCharacterId();
        if (pid == null) {
            return;
        }
        Long last = lastPickByPlayer.get(pid);
        if (last != null && now - last < PICK_COOLDOWN_MS) {
            return;
        }
        double sx = player.getX();
        double sz = player.getZ();
        double reachSq = PICK_REACH * PICK_REACH;
        GroundItemManager.GroundItem best = null;
        double bestD2 = reachSq;
        for (GroundItemManager.GroundItem gi : items) {
            double dx = sx - gi.x;
            double dz = sz - gi.z;
            double d2 = dx * dx + dz * dz;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = gi;
            }
        }
        if (best == null) {
            return;
        }
        lastPickByPlayer.put(pid, now);
        // 掷点实例直接入背包（保留地面属性）；背包满则返回 null（原地保留，等玩家整理）
        var granted = itemService.grantInstanceToBag(p, best.item);
        if (granted == null) {
            log.info("[GroundItemAOI] {} pick gid={} name={}: bag full, 保留地面",
                session.getCharacterName(), best.id,
                best.item.getTemplate() != null ? best.item.getTemplate().getName() : "?");
            return;
        }
        groundItems.remove(best.mapId, best.id);
        session.send(buildDisappear(best.id));
        log.info("[GroundItemAOI] {} 自动拾取 gid={} itemListId={} name={} @({},{},{})",
            session.getCharacterName(), best.id, granted.getItemListId(),
            granted.getTemplate() != null ? granted.getTemplate().getName() : "?",
            (float) best.x, (float) best.y, (float) best.z);
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
