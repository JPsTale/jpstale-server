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
 *   金币掉落物**带金额**（见 GroundItem.money）—— 现在金币是真的掉在地上的道具，不再直接入账。
 * - 两层挤压（原版 cnt2 循环）：先在不超限时直接放；超过且存在 Level=0 则覆盖其一；
 *   全是 Level=1 → 新掉落丢弃（rsItemBuffOverCount++，返回 null）。
 */
@Slf4j
@Component
public class GroundItemManager {

    /** 每地图活跃地面物上限（原版 STG_ITEM_MAX） */
    public static final int STG_ITEM_MAX = 1024;

    /**
     * **怪物掉落**中"私有战利品"只给归属者看的窗口长度：5 秒。
     *
     * 只适用于 `dropispublic = 0` 的怪物掉落（击杀者的战利品）。
     * **玩家主动丢到地上的东西不走这条路** —— 原版那条分支直接
     * `SendStgItemToNearUsers`（立即广播给所有人，见 `ItemNetworkHandler.handleDropItem` 注释）。
     *
     * 出处见 `GroundItem.privateUntil` —— 经典三棵树与 EU 都在生成时给掉落可见时刻 `+= 5000`，
     * 此后再靠周期批量补发变成公共掉落。这是**唯一**一处定义
     *（`GroundItemAOI` 的可见性判定与拾取校验都读它，避免两处各写一个数）。
     */
    public static final long PRIVATE_WINDOW_MS = 5000L;

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
        /**
         * **私有窗口截止时刻**（仅"怪物掉落的私有战利品"有意义，即 `ownerId != 0`）：
         * 在此之前只有归属者看得见/捡得走，之后**对所有人可见、且谁都能捡**。
         *
         * 依据（经典三棵树一致）：私有掉落在生成时被 `dwCreateTime += 5000`（J_Server/ex-machina
         * `OnSever.cpp`、NewSourcePT `:9372`），注释即"5 秒后才对别人可见"；EU 同构
         * （`unitserver.cpp:1072` 的 `psItemD->dwDropTime += 5000 //for other players`）。
         * 之后周期性的批量补发（`SendStgItems`；EU `SendStageItem` 每 8 秒）就把该物品发给
         * 视野内**所有**玩家了。而四棵树在**拾取端都没有归属校验** ⇒ 原版里"私有"只是
         * "先不告诉你它在哪"，5 秒后它就是公共掉落。
         *
         * ⚠ **玩家主动丢到地上的东西不走这条路**：那条分支（ex-machina `OnSever.cpp:18784`）
         * 直接 `SendStgItemToNearUsers` 且没有 `+5000`、没有归属 ⇒ `ownerId` 传 0，立即公开。
         * （用户 2026-09-16 纠正："玩家丢弃原版是立即看到，没有 5 秒限制"。）
         *
         * ⚠ 我们过去把**所有** `ownerId != 0` 写成"永久只有归属者可见"
         * （用户 2026-09-16 实测：看不到别人打怪掉的东西）。
         */
        public final long privateUntil;
        public final long expireAt;
        /** 挤压级：1=不可覆盖（材料/装备），0=可被新掉落覆盖（金币/药水）。对齐原版 StgItems[].Level */
        public final int level;
        /**
         * **金币金额**（仅金币掉落物 > 0）：拾取时入账用（原版 `sITEMINFO.Money`）。
         * 地面物是内存对象、不进 DB，所以金额挂在这里而不是物品实例上。
         */
        public final int money;

        public GroundItem(long id, ItemInstance item, int mapId, double x, double y, double z, long ownerId,
                          long privateUntil, long expireAt, int level, int money) {
            this.id = id;
            this.item = item;
            this.mapId = mapId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ownerId = ownerId;
            this.privateUntil = privateUntil;
            this.expireAt = expireAt;
            this.level = level;
            this.money = money;
        }

        /**
         * 现在是否仍处于私有窗口（只有归属者能看见/捡）。
         * `ownerId == 0`（公共掉落）恒 false。
         */
        public boolean isPrivateAt(long now) {
            return ownerId != 0 && now < privateUntil;
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
        if (item == null || item.getTemplate() == null) {
            return 1;
        }
        // **金币掉落物**（原版 sinGG1）先判：它有**没有 classItem**（原版 Gold 行即如此），
        // 若走下面那个 "classItem == null → 1" 的守卫就会被判成不可覆盖。
        Integer code = item.getItemCode();
        if (ItemRules.isGoldFamily(code == null ? 0 : code)) {
            return 0;
        }
        Integer classItem = item.getTemplate().getClassItem();
        if (classItem == null) {
            return 1;
        }
        // 药水（classItem=8192）同样可被覆盖
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
        long nowMs = System.currentTimeMillis();
        // 私有窗口：ownerId != 0 时，前 PRIVATE_WINDOW_MS 只发给归属者（见 GroundItem.privateUntil）
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

    /**
     * 某地图当前全部未过期地面物品（供 AOI 每 tick reconcile）。
     * 顺带把过期项就地剔除（地面物 TTL 到期的清扫点之一）。
     */
    /**
     * **全部**地图的地面物（顺带剔除过期项）。
     *
     * 可见性用它而不是 {@link #listByMap}：地图边界是人为切分的，掉落物就在边界另一侧时
     * 按图取会"看不见"（用户 2026-09-16 报的跨边界不可见）。玩家 AOI 与怪物/NPC 都已统一到坐标口径。
     * 与 `listByMap` 相比是**更少**的分配（一个列表 vs 每图一个）。
     */
    public java.util.List<GroundItem> listAll() {
        long now = System.currentTimeMillis();
        int size = 0;
        for (Map<Long, GroundItem> m : byMap.values()) {
            size += m.size();
        }
        java.util.List<GroundItem> out = new java.util.ArrayList<>(size);
        for (Map<Long, GroundItem> m : byMap.values()) {
            for (java.util.Map.Entry<Long, GroundItem> e : m.entrySet()) {
                GroundItem gi = e.getValue();
                if (gi.isExpired(now)) {
                    m.remove(e.getKey());
                    continue;
                }
                out.add(gi);
            }
        }
        return out;
    }

    /**
     * 按**全局唯一 id** 取地面物（不按图过滤）。
     *
     * 拾取校验用它：真正的门槛是**与玩家的距离**（见 `ItemNetworkHandler` 的 PICKUP_RANGE），
     * 而物品 id 由本类的 `idSeq` 全局分配、跨图唯一 ⇒ 没必要再拿 mapId 卡一道。
     * 否则"看得见（AOI 按坐标）却捡不到（查找按图）"会成为一个新坑。
     */
    public GroundItem byIdAnyMap(long id) {
        for (Map<Long, GroundItem> m : byMap.values()) {
            GroundItem gi = m.get(id);
            if (gi != null && !gi.isExpired(System.currentTimeMillis())) {
                return gi;
            }
        }
        return null;
    }

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