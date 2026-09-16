package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.GroundItem;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.item.GroundItemManager;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_GroundItemAppear;
import org.jpstale.server.proto.base.S2C_GroundItemDisappear;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地面物品 AOI：与 {@link MonsterAOI} 同构的可见性同步。
 *
 * 玩家进入 / 走动 / 掉落物增减时，向视野内玩家推送
 * S2C_GroundItemAppear / S2C_GroundItemDisappear：
 *  - 进入 CONNECT(1000) → Appear
 *  - 超出 DISCONNECT(1600) → Disappear（1000~1600 是滞回区，见 {@link AOIManager}）
 * 两个距离都在 {@link AOIManager} 里，四类 AOI 共用。
 *  - 被移除（过期/被拾取/被挤掉）：由 `GroundItemManager.drainRemoved()` 显式通知，见 notifyRemoved
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

    /**
     * 玩家进入世界/换图时清空他的地面物品可见集，并逐条补发 Disappear（换图时客户端不清场）。
     * 可见集以 charId 为键跨会话保留：不清则重进时 `add` 恒 false ⇒ 一条 Appear 都不发。
     */
    public void clearVisible(PlayerSession session) {
        if (session == null) {
            return;
        }
        Long pid = session.getCharacterId();
        if (pid == null) {
            return;
        }
        Set<Long> visible = visibleByPlayer.get(pid);
        if (visible == null) {
            return;
        }
        synchronized (visible) {
            for (long gid : visible) {
                session.send(buildDisappear(gid));
            }
            visible.clear();
        }
    }

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
            // **按坐标**同步，不按图（与怪物/NPC AOI 同一口径）：地图边界是人为切分的，
            // 只取本图的掉落物会让"就在边界另一侧"的那件东西看不见（用户 2026-09-16）。
            reconcile(e, groundItems.listAll());
        }
        // 本 tick 被移除的物品（过期/被拾取/被新掉落挤掉）：**显式通知**观察者 Disappear。
        // 地面物只在 GroundItemManager 里被移除，那边逐条登记（`removedQueue`），这里取走并通知 ——
        // **谁移除谁登记、AOI 只负责通知**，于是本类不需要任何"猜谁消失了"的兜底逻辑。
        for (GroundItem gone : groundItems.drainRemoved()) {
            notifyRemoved(gone.getId());
        }
        // 清理已离线/未 playing 会话的残留可见集
        visibleByPlayer.keySet().removeIf(pid -> !active.contains(pid));
    }

    private void reconcile(PlayerEntity player, List<GroundItem> items) {
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

        for (GroundItem gi : items) {
            long id = gi.getId();
            // 私有战利品只在私有窗口内仅 owner 可见；窗口一过即公共掉落（见 GroundItem.privateUntil）。
            if (gi.isPrivateAt(System.currentTimeMillis()) && gi.ownerId != pid) {
                if (visible.remove(id)) {
                    session.send(buildDisappear(id));
                }
                continue;
            }
            double dx = sx - gi.getX();
            double dz = sz - gi.getZ();
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
    }

    /**
     * 物品已从世界移除 → 给仍把它当可见的观察者发 Disappear 并摘出可见集（由 `drainRemoved()` 驱动）。
     * **不要在 reconcile 里加对账式清理**：那种写法隐含要求候选集完整，按图分次调用会误判成"消失"→ 闪烁。
     */
    private void notifyRemoved(long id) {
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            Set<Long> visible = e.getValue();
            synchronized (visible) {
                if (visible.remove(id)) {
                    PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                    if (s != null) {
                        s.send(buildDisappear(id));
                    }
                }
            }
        }
    }

    /**
     * 构造 S2C_GroundItemAppear（含掉落模型码，客户端据此加载 DropItem 模型 `dropitem/it{码}.smd`）。
     *
     * 模型码一律取 `codeImg2` —— 即原版 Drop Item Image（EU `itemserver.cpp` 的 SELECT 列 6，
     * 变量名 `szDropItem`）。它是**地面模型码**，与 `codeImg1`（物品自有图标码）不同：
     * - 药水/法球/力石/配方等高级版 `codeImg2` 指向**基础版**模型（如 `pl104 → pl101`、
     *   `fo101 → os101`、`gp102 → GP101`、`dr209 → DR101`、`ec104 → ec101`），共用一只模型；
     * - 金币行 `codeImg2='DRCOIN'`（`codeImg1='GG101'` 没有模型），同样踩中这条规则。
     * 取错了会让客户端 404 回落到旗帜（曾按"普通物品用 codeImg1"实现，导致 46 个方位物品
     * 掉出来全是旗帜，2026-09-15 已修正。数据核对见 AGENTS.md「掉落模型」调查记录）。
     * `codeImg2` 为空时回退 `codeImg1`（防御，DB 全量 1036 行实测无空值）。
     */
    private ServerMessage buildAppear(GroundItem gi) {
        Integer code = gi.item.getItemCode();
        String name = gi.item.getTemplate() != null && gi.item.getTemplate().getName() != null
            ? gi.item.getTemplate().getName() : "";
        String dorp = "";
        if (gi.item.getTemplate() != null) {
            String pick = gi.item.getTemplate().getCodeImg2();
            if (pick == null || pick.isEmpty()) {
                pick = gi.item.getTemplate().getCodeImg1();
            }
            dorp = pick != null ? pick : "";
        }
        return ServerMessage.newBuilder()
            .setGroundItemAppear(S2C_GroundItemAppear.newBuilder()
                .setItem(CommonProto.GroundItemProto.newBuilder()
                    .setGroundItemId(gi.getId())
                    .setItemId(code == null ? 0 : code)
                    .setQuantity(gi.item.getCount())
                    .setMoney(gi.money)
                    .setPosition(CommonProto.Position.newBuilder()
                        .setX((float) gi.getX()).setY((float) gi.getY()).setZ((float) gi.getZ()).build())
                    .setOwnerId(gi.ownerId)
                    .setExpireTime(gi.expireAt)
                    .setName(name)
                    .setDorpItem(dorp)
                    .build())
                .build())
            .build();
    }

    private ServerMessage buildDisappear(long groundItemId) {
        return ServerMessage.newBuilder()
            .setGroundItemDisappear(S2C_GroundItemDisappear.newBuilder()
                .setGroundItemId(groundItemId).build())
            .build();
    }
}
