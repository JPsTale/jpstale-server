package org.jpstale.server.game.entity;

import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.Npc;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局实体注册表 —— 所有实体的 O(1) 按 id 查找 + 按图分组查询。
 *
 * <p>职责：各 SpawnService / Manager 注册/注销实体时同步写入本表，
 * CombatService、AOIManager 等只读方直接查本表，不再依赖具体 SpawnService。
 *
 * <p>内部结构：每种实体各一组
 * <pre>
 *   Map<Long, T>        byId       —— O(1) 按 id 查
 *   Map<Integer, List<T>> byMap    —— 按 mapId 分组（给 AOI / 区域加载用）
 * </pre>
 */
@Component
public class EntityRegistry {

    // ==================== Monster ====================

    private final Map<Long, Monster> monstersById = new ConcurrentHashMap<>();
    private final Map<Integer, List<Monster>> monstersByMap = new ConcurrentHashMap<>();

    public void register(Monster m) {
        monstersById.put(m.getId(), m);
        monstersByMap.computeIfAbsent(m.getMapId(), k -> new ArrayList<>()).add(m);
    }

    public void unregisterMonster(long id) {
        Monster m = monstersById.remove(id);
        if (m != null) {
            List<Monster> list = monstersByMap.get(m.getMapId());
            if (list != null) list.remove(m);
        }
    }

    public Monster findMonster(long id) {
        return monstersById.get(id);
    }

    public List<Monster> monstersByMap(int mapId) {
        return monstersByMap.getOrDefault(mapId, List.of());
    }

    public Collection<Monster> allMonsters() {
        return monstersById.values();
    }

    // ==================== Npc ====================

    private final Map<Long, Npc> npcsById = new ConcurrentHashMap<>();
    private final Map<Integer, List<Npc>> npcsByMap = new ConcurrentHashMap<>();

    public void register(Npc n) {
        npcsById.put(n.getId(), n);
        npcsByMap.computeIfAbsent(n.getMapId(), k -> new ArrayList<>()).add(n);
    }

    public void unregisterNpc(long id) {
        Npc n = npcsById.remove(id);
        if (n != null) {
            List<Npc> list = npcsByMap.get(n.getMapId());
            if (list != null) list.remove(n);
        }
    }

    public Npc findNpc(long id) {
        return npcsById.get(id);
    }

    public List<Npc> npcsByMap(int mapId) {
        return npcsByMap.getOrDefault(mapId, List.of());
    }

    public Collection<Npc> allNpcs() {
        return npcsById.values();
    }

    // ==================== GroundItem ====================

    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, GroundItem>> groundItemsByMap = new ConcurrentHashMap<>();

    /** 获取或创建指定地图的地面物品内层表（GroundItemManager 的 squeeze/expire 直接操作此表） */
    public ConcurrentHashMap<Long, GroundItem> getOrCreateGroundItemMap(int mapId) {
        return groundItemsByMap.computeIfAbsent(mapId, k -> new ConcurrentHashMap<>());
    }

    /** 所有地图的地面物品内层表（expireSweep / listAll 遍历用） */
    public Collection<ConcurrentHashMap<Long, GroundItem>> allGroundItemMaps() {
        return groundItemsByMap.values();
    }

    /** 注册地面物品（委托 GroundItemManager.add 内部调用） */
    public void registerGroundItem(GroundItem gi) {
        getOrCreateGroundItemMap(gi.getMapId()).put(gi.getId(), gi);
    }

    /** 按图+id 移除地面物品（条件移除，用于挤压/过期） */
    public boolean removeGroundItemIfPresent(int mapId, long id, GroundItem expect) {
        ConcurrentHashMap<Long, GroundItem> m = groundItemsByMap.get(mapId);
        return m != null && m.remove(id, expect);
    }

    /** 按图+id 移除地面物品（无条件移除，用于拾取） */
    public GroundItem removeGroundItem(int mapId, long id) {
        ConcurrentHashMap<Long, GroundItem> m = groundItemsByMap.get(mapId);
        return m == null ? null : m.remove(id);
    }

    /** 按全局 id 查地面物（跨图扫描） */
    public GroundItem findGroundItem(long id) {
        for (ConcurrentHashMap<Long, GroundItem> m : groundItemsByMap.values()) {
            GroundItem gi = m.get(id);
            if (gi != null) return gi;
        }
        return null;
    }

    /** 全部地面物品（只读，AOI reconcile 用） */
    public Collection<GroundItem> allGroundItems() {
        List<GroundItem> out = new ArrayList<>();
        for (ConcurrentHashMap<Long, GroundItem> m : groundItemsByMap.values()) {
            out.addAll(m.values());
        }
        return out;
    }

    // ==================== Player ====================

    private final Map<Long, PlayerEntity> playersById = new ConcurrentHashMap<>();
    private final Map<Integer, List<PlayerEntity>> playersByMap = new ConcurrentHashMap<>();

    public void register(PlayerEntity p) {
        playersById.put(p.getId(), p);
        playersByMap.computeIfAbsent(p.getMapId(), k -> new ArrayList<>()).add(p);
    }

    public void unregisterPlayer(long id) {
        PlayerEntity p = playersById.remove(id);
        if (p != null) {
            List<PlayerEntity> list = playersByMap.get(p.getMapId());
            if (list != null) list.remove(p);
        }
    }

    public PlayerEntity findPlayer(long id) {
        return playersById.get(id);
    }

    public List<PlayerEntity> playersByMap(int mapId) {
        return playersByMap.getOrDefault(mapId, List.of());
    }

    public Collection<PlayerEntity> allPlayers() {
        return playersById.values();
    }
}
