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
 *
 * 掉落上限/挤压：对齐原版 OnSever.cpp AddItem (STG_ITEM_MAX=1024)：
 * - 每地图活跃地面物上限 1024（onserver.h STG_ITEM_MAX）。
 * - Level=1（非金币/非药水：材料/装备/兽皮等）TTL 3min，永不被挤压覆盖，只靠过期清除。
 * - Level=0（金币 sinGG1 / 红蓝绿药 sinPL1/sinPS1/sinPM1）TTL 90s；容量满时被新掉落覆盖挤掉。
 * - 两层挤压（原版 cnt2 循环）：先在不超限时直接放；超过且存在 Level=0 则覆盖其一；
 *   全是 Level=1 → 新掉落丢弃（rsItemBuffOverCount++，返回 null）。
 */
@Slf4j
@Component
public class GroundItemManager {

    /** 每地图活跃地面物上限（原版 STG_ITEM_MAX） */
    public static final int STG_ITEM_MAX = 1024;

    /** 原版 STG_ITEM_WAIT_TIME：Level=1 物品 3 分钟 */
    public static final long TTL_HIGH_MS = 3 * 60 * 1000L;

    /** 原版 STG_ITEM_WAIT_TIME_LOW：Level=0 金币/药水 90 秒 */
    public static final long TTL_LOW_MS = 90 * 1000L;

    public static final long DEFAULT_TTL_MS = TTL_HIGH_MS;

    /** 被丢弃的掉落计数（原版 rsItemBuffOverCount） */
    private final AtomicLong droppedOverCount = new AtomicLong();

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
        /** 挤压级：1=不可覆盖（材料/装备），0=可被新掉落覆盖（金币/药水）。对齐原版 StgItems[].Level */
        public final int level;

        public GroundItem(long id, ItemInstance item, int mapId, double x, double y, double z, long ownerId, long expireAt, int level) {
            this.id = id;
            this.item = item;
            this.mapId = mapId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ownerId = ownerId;
            this.expireAt = expireAt;
            this.level = level;
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

    /** 是否 Level=0 挤压级（金币/药水 TTL 90s 且可被覆盖；原版 sinGG1/sinPL1/sinPS1/sinPM1 语义） */
    private static int levelOf(ItemInstance item) {
        if (item == null || item.getTemplate() == null || item.getTemplate().getClassItem() == null) {
            return 1;
        }
        // 药水 classItem=8192；金币无 classItem（我们金币走 player.gold, 不走地面物）
        return item.getTemplate().getClassItem() == 8192 ? 0 : 1;
    }

    /** 默认 TTL：Level0(金币/药水) 90s，Level1(材料/装备) 3min */
    private static long defaultTtlFor(int level) {
        return level == 0 ? TTL_LOW_MS : TTL_HIGH_MS;
    }

    /**
     * 投放一件地面物品；ttlMs<=0 按类别默认有效期。
     * 该地图已满（≥STG_ITEM_MAX）时：
     * 1. 覆盖一个 Level=0（金币/药水）腾位；
     * 2. 全是 Level=1（不可覆盖）→ 丢弃该掉落（rsItemBuffOverCount++），返回 null。
     */
    public GroundItem add(ItemInstance item, int mapId, double x, double y, double z, long ownerId, long ttlMs) {
        int level = levelOf(item);
        long ttl = ttlMs > 0 ? ttlMs : defaultTtlFor(level);
        ConcurrentHashMap<Long, GroundItem> m = byMap.computeIfAbsent(mapId, k -> new ConcurrentHashMap<>());
        if (m.size() >= STG_ITEM_MAX) {
            // 挤压：过期先清；再覆盖一个 Level=0（金币/药水）腾位；全 Level=1 则丢弃该掉落
            GroundItem victim = null;
            for (GroundItem gi : m.values()) {
                if (gi.isExpired(System.currentTimeMillis())) {
                    m.remove(gi.id, gi);
                } else if (gi.level == 0 && victim == null) {
                    victim = gi;
                }
            }
            if (victim == null) {
                long dropped = droppedOverCount.incrementAndGet();
                log.warn("[GroundItem] mapId={} 满({}) 且全为 Level=1 → 丢弃掉落 itemListId={} (累计丢弃 {})",
                    mapId, m.size(), item.getItemListId(), dropped);
                return null;
            }
            m.remove(victim.id);
            log.info("[GroundItem] mapId={} 满({}) → 覆盖挤掉 Level0 gid={} 为新掉落腾位",
                mapId, m.size(), victim.id);
        }
        long id = idSeq.incrementAndGet();
        GroundItem gi = new GroundItem(id, item, mapId, x, y, z, ownerId, System.currentTimeMillis() + ttl, level);
        m.put(id, gi);
        log.info("[GroundItem] add id={} mapId={} itemListId={} code={} name={} @({},{},{}) owner={} ttl={}ms level={}",
            id, mapId, item.getItemListId(), item.getItemCode(), item.getTemplate() != null ? item.getTemplate().getName() : "?",
            (float) x, (float) y, (float) z, ownerId, ttl, level);
        return gi;
    }

    /** 自上次以来的丢弃计数（原版 rsItemBuffOverCount），监控用 */
    public long droppedOverCount() {
        return droppedOverCount.get();
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