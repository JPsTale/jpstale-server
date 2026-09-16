package org.jpstale.server.game.item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.GroundItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 地面物品管理（GM 刷物 / 拾取的基础状态）。
 *
 * 存储委托 {@link EntityRegistry}（二级 map：mapId → (itemId → GroundItem)），
 * 本类只保留业务逻辑：投放/挤压/过期清扫/removedQueue。
 *
 * 掉落上限/挤压：对齐原版 OnSever.cpp AddItem (STG_ITEM_MAX=1024)：
 * - 每地图活跃地面物上限 1024（onserver.h STG_ITEM_MAX）。
 * - Level=1（非金币/非药水：材料/装备/兽皮等）TTL 3min，永不被挤压覆盖，只靠过期清除。
 * - Level=0（金币 sinGG1 / 红蓝绿药 sinPL1/sinPS1/sinPM1）TTL 90s；容量满时被新掉落覆盖挤掉。
 *   金币掉落物**带金额**（见 GroundItem.money）—— 现在金币是真的掉在地上的道具，不再直接入账。
 * - 两层挤压（原版 cnt2 循环）：先在不超限时直接放；超过且存在 Level=0 则覆盖其一；
 *   全是 Level=1 → 新掉落丢弃（rsItemBuffOverCount++，返回 null）。
 */
@Slf4j
@Component
public class GroundItemManager {

    /** 每地图活跃地面物上限（原版 STG_ITEM_MAX） */
    public static final int STG_ITEM_MAX = 1024;

    /** 私有战利品的可见窗口：5 秒（出处见 `GroundItem.privateUntil`）。只适用于非公共的怪物掉落。 */
    public static final long PRIVATE_WINDOW_MS = 5000L;

    /** 原版 STG_ITEM_WAIT_TIME：Level=1 物品 3 分钟 */
    public static final long TTL_HIGH_MS = 3 * 60 * 1000L;

    /** 原版 STG_ITEM_WAIT_TIME_LOW：Level=0 金币/药水 90 秒 */
    public static final long TTL_LOW_MS = 90 * 1000L;

    public static final long DEFAULT_TTL_MS = TTL_HIGH_MS;

    /** 被丢弃的掉落计数（原版 rsItemBuffOverCount） */
    private final AtomicLong droppedOverCount = new AtomicLong();

    private final AtomicLong idSeq = new AtomicLong(1);

    @Autowired
    private EntityRegistry entityRegistry;

    /**
     * 本 tick 被移除的地面物（过期清扫 / 被新掉落挤掉 / 被拾取）。
     * `GroundItemAOI` 每 tick 取走并给观察者发 Disappear —— **谁移除谁登记**，AOI 不做任何对账。
     */
    private final java.util.Queue<GroundItem> removedQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 取走本 tick 被移除的物品（清空队列） */
    public java.util.List<GroundItem> drainRemoved() {
        java.util.List<GroundItem> out = new java.util.ArrayList<>();
        GroundItem gi;
        while ((gi = removedQueue.poll()) != null) {
            out.add(gi);
        }
        return out;
    }

    /** 过期清扫：**唯一**的过期移除点（`GameServer.tick` 每 tick 调）。查询只判定、不改状态。 */
    public int expireSweep() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (ConcurrentHashMap<Long, GroundItem> m : entityRegistry.allGroundItemMaps()) {
            for (GroundItem gi : m.values()) {
                if (gi.isExpired(now) && m.remove(gi.getId(), gi)) {
                    removedQueue.add(gi);
                    n++;
                }
            }
        }
        return n;
    }

    /** 是否 Level=0 挤压级（金币/药水 TTL 90s 且可被覆盖；原版 sinGG1/sinPL1/sinPS1/sinPM1 语义） */
    private static int levelOf(ItemInstance item) {
        if (item == null || item.getTemplate() == null) {
            return 1;
        }
        Integer code = item.getItemCode();
        if (ItemRules.isGoldFamily(code == null ? 0 : code)) {
            return 0;
        }
        Integer classItem = item.getTemplate().getClassItem();
        if (classItem == null) {
            return 1;
        }
        return ItemClass.isPotion(classItem) ? 0 : 1;
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
        return add(item, mapId, x, y, z, ownerId, ttlMs, 0);
    }

    /** 投放一件**带金额**的地面物（金币掉落物用；money=0 等价于普通地面物）。 */
    public GroundItem add(ItemInstance item, int mapId, double x, double y, double z, long ownerId, long ttlMs, int money) {
        int level = levelOf(item);
        long ttl = ttlMs > 0 ? ttlMs : defaultTtlFor(level);
        ConcurrentHashMap<Long, GroundItem> m = entityRegistry.getOrCreateGroundItemMap(mapId);
        if (m.size() >= STG_ITEM_MAX) {
            GroundItem victim = null;
            for (GroundItem gi : m.values()) {
                if (gi.isExpired(System.currentTimeMillis())) {
                    if (m.remove(gi.getId(), gi)) {
                        removedQueue.add(gi);
                    }
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
            m.remove(victim.getId());
            removedQueue.add(victim);
            log.info("[GroundItem] mapId={} 满({}) → 覆盖挤掉 Level0 gid={} 为新掉落腾位",
                mapId, m.size(), victim.getId());
        }
        long id = idSeq.incrementAndGet();
        long nowMs = System.currentTimeMillis();
        long privateUntil = ownerId != 0 ? nowMs + PRIVATE_WINDOW_MS : 0L;
        GroundItem gi = new GroundItem(id, item, mapId, x, y, z, ownerId, privateUntil, nowMs + ttl, level, money);
        m.put(id, gi);
        log.info("[GroundItem] add id={} mapId={} itemListId={} code={} name={} money={} @({},{},{}) owner={} ttl={}ms level={}",
            id, mapId, item.getItemListId(), item.getItemCode(), item.getTemplate() != null ? item.getTemplate().getName() : "?",
            money, (float) x, (float) y, (float) z, ownerId, ttl, level);
        return gi;
    }

    /** 自上次以来的丢弃计数（原版 rsItemBuffOverCount），监控用 */
    public long droppedOverCount() {
        return droppedOverCount.get();
    }

    /** 全部地图的地面物（顺带滤掉过期项）。AOI 按坐标判可见性，故不能按图取。 */
    public java.util.List<GroundItem> listAll() {
        long now = System.currentTimeMillis();
        int size = 0;
        for (ConcurrentHashMap<Long, GroundItem> m : entityRegistry.allGroundItemMaps()) {
            size += m.size();
        }
        java.util.List<GroundItem> out = new java.util.ArrayList<>(size);
        for (ConcurrentHashMap<Long, GroundItem> m : entityRegistry.allGroundItemMaps()) {
            for (java.util.Map.Entry<Long, GroundItem> e : m.entrySet()) {
                GroundItem gi = e.getValue();
                if (gi.isExpired(now)) {
                    continue;
                }
                out.add(gi);
            }
        }
        return out;
    }

    /** 按全局唯一 id 取地面物（不按图过滤） */
    public GroundItem byIdAnyMap(long id) {
        for (ConcurrentHashMap<Long, GroundItem> m : entityRegistry.allGroundItemMaps()) {
            GroundItem gi = m.get(id);
            if (gi != null && !gi.isExpired(System.currentTimeMillis())) {
                return gi;
            }
        }
        return null;
    }

    public java.util.List<GroundItem> listByMap(int mapId) {
        ConcurrentHashMap<Long, GroundItem> m = entityRegistry.getOrCreateGroundItemMap(mapId);
        long now = System.currentTimeMillis();
        java.util.List<GroundItem> out = new java.util.ArrayList<>(m.size());
        for (java.util.Map.Entry<Long, GroundItem> e : m.entrySet()) {
            GroundItem gi = e.getValue();
            if (!gi.isExpired(now)) {
                out.add(gi);
            }
        }
        return out;
    }

    public GroundItem findNearest(int mapId, double x, double z, double range) {
        ConcurrentHashMap<Long, GroundItem> m = entityRegistry.getOrCreateGroundItemMap(mapId);
        long now = System.currentTimeMillis();
        double rr = range * range;
        double bestDist2 = Double.MAX_VALUE;
        GroundItem best = null;
        for (Map.Entry<Long, GroundItem> e : m.entrySet()) {
            GroundItem gi = e.getValue();
            if (gi.isExpired(now)) {
                continue;
            }
            double dx = gi.getX() - x;
            double dz = gi.getZ() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = gi;
            }
        }
        return (best != null && bestDist2 <= rr) ? best : null;
    }

    public GroundItem byId(int mapId, long id) {
        ConcurrentHashMap<Long, GroundItem> m = entityRegistry.getOrCreateGroundItemMap(mapId);
        GroundItem gi = m.get(id);
        if (gi == null || gi.isExpired(System.currentTimeMillis())) {
            return null;
        }
        return gi;
    }

    /** 移除地面物品（拾取/过期通知后调用）；返回被移除项 */
    public GroundItem remove(int mapId, long id) {
        ConcurrentHashMap<Long, GroundItem> m = entityRegistry.getOrCreateGroundItemMap(mapId);
        GroundItem gi = m.remove(id);
        if (gi != null) {
            removedQueue.add(gi);
            log.info("[GroundItem] remove id={} mapId={} name={} @({},{},{})",
                id, mapId, gi.item.getTemplate() != null ? gi.item.getTemplate().getName() : "?", (float) gi.getX(), (float) gi.getY(), (float) gi.getZ());
        }
        return gi;
    }
}
