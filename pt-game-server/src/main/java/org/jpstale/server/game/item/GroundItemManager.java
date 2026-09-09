package org.jpstale.server.game.item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 地面物品管理（GM 刷物 / 拾取的基础状态）。
 *
 * 全地图全局表：mapId → (groundItemId → GroundItem)。
 * 地面物品由掷点实例组成（保留随机属性），拾取后直接把该实例入背包。
 */
@Slf4j
@Component
public class GroundItemManager {

    public static final long DEFAULT_TTL_MS = 5 * 60 * 1000;

    private final AtomicLong idSeq = new AtomicLong(1);
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, GroundItem>> byMap = new ConcurrentHashMap<>();

    /** 地面物品（字段不可变） */
    public static class GroundItem {
        public final long id;
        public final ItemInstance item;
        public final int mapId;
        public final double x, y, z;
        public final long ownerId;
        public final long expireAt;

        public GroundItem(long id, ItemInstance item, int mapId, double x, double y, double z, long ownerId, long expireAt) {
            this.id = id;
            this.item = item;
            this.mapId = mapId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ownerId = ownerId;
            this.expireAt = expireAt;
        }

        public boolean isExpired(long now) {
            return expireAt <= now;
        }

        /** 显示名（模板不存在返回原始 itemCode 字符串） */
        public String displayName() {
            if (item.getTemplate() != null && item.getTemplate().getName() != null && !item.getTemplate().getName().isEmpty()) {
                return item.getTemplate().getName();
            }
            return "item#" + item.getItemCode();
        }
    }

    /** 投放一件地面物品；ttlMs<=0 用默认有效期 */
    public GroundItem add(ItemInstance item, int mapId, double x, double y, double z, long ownerId, long ttlMs) {
        long id = idSeq.incrementAndGet();
        long ttl = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        GroundItem gi = new GroundItem(id, item, mapId, x, y, z, ownerId, System.currentTimeMillis() + ttl);
        byMap.computeIfAbsent(mapId, k -> new ConcurrentHashMap<>()).put(id, gi);
        log.info("[GroundItem] add id={} mapId={} itemListId={} code={} name={} @({},{},{}) owner={} ttl={}ms",
            id, mapId, item.getItemListId(), item.getItemCode(), item.getTemplate() != null ? item.getTemplate().getName() : "?",
            (float) x, (float) y, (float) z, ownerId, ttl);
        return gi;
    }

    /**
     * 某地图当前全部未过期地面物品（供 AOI 每 tick reconcile）。
     * 顺带把过期项就地剔除（地面物 TTL 到期的清扫点之一）。
     */
    public java.util.List<GroundItem> listByMap(int mapId) {
        Map<Long, GroundItem> m = byMap.get(mapId);
        if (m == null) {
            return java.util.List.of();
        }
        long now = System.currentTimeMillis();
        java.util.List<GroundItem> out = new java.util.ArrayList<>(m.size());
        for (java.util.Map.Entry<Long, GroundItem> e : m.entrySet()) {
            GroundItem gi = e.getValue();
            if (gi.isExpired(now)) {
                m.remove(e.getKey());
                continue;
            }
            out.add(gi);
        }
        return out;
    }

    /**
     * 在地面表中找距 (x,z) 最近且未过期的地面物品；两种语义：
     * - 无视具体 id（拾取：服务端距离裁决）
     * - range 内无物品返回 null。
     * 单位 = 地图坐标；过期项就地剔除。
     */
    public GroundItem findNearest(int mapId, double x, double z, double range) {
        Map<Long, GroundItem> m = byMap.get(mapId);
        if (m == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        double rr = range * range;
        long bestId = -1;
        double bestDist2 = Double.MAX_VALUE;
        GroundItem best = null;
        for (Map.Entry<Long, GroundItem> e : m.entrySet()) {
            GroundItem gi = e.getValue();
            if (gi.isExpired(now)) {
                m.remove(e.getKey());
                continue;
            }
            double dx = gi.x - x;
            double dz = gi.z - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = gi;
                bestId = e.getKey();
            }
        }
        return (best != null && bestDist2 <= rr) ? best : null;
    }

    /** 取指定地面物品（跨 findNearest 的距离校验用）；不存在/过期返回 null */
    public GroundItem byId(int mapId, long id) {
        Map<Long, GroundItem> m = byMap.get(mapId);
        if (m == null) {
            return null;
        }
        GroundItem gi = m.get(id);
        if (gi == null) {
            return null;
        }
        if (gi.isExpired(System.currentTimeMillis())) {
            m.remove(id);
            return null;
        }
        return gi;
    }

    /** 移除地面物品（拾取/过期通知后调用）；返回被移除项 */
    public GroundItem remove(int mapId, long id) {
        Map<Long, GroundItem> m = byMap.get(mapId);
        GroundItem gi = m == null ? null : m.remove(id);
        if (gi != null) {
            log.info("[GroundItem] remove id={} mapId={} name={} @({},{},{})",
                id, mapId, gi.item.getTemplate() != null ? gi.item.getTemplate().getName() : "?", (float) gi.x, (float) gi.y, (float) gi.z);
        }
        return gi;
    }
}