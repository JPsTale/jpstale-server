package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.MonsterList;
import org.jpstale.dao.gamedb.mapper.MonsterListMapper;
import org.jpstale.server.game.service.AiEngine;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.MapManager;
import org.jpstale.server.game.model.GameMap;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterWave;
import org.jpstale.server.game.model.MonsterSpawnConfig;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.model.SpawnPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 怪物生成服务
 * 参照 PristonTale-EU / ex-machina 的刷怪逻辑：
 * - 启动时不刷怪
 * - 每 tick 循环每张地图，只在玩家附近出生点刷怪
 * - 每个出生点独立上限
 * - 加权随机选怪物类型
 */
@Slf4j
@Component
public class MonsterSpawnService {

    /** 每 tick 最大刷新间隔（约16次/秒） */
    private static final int SPAWN_CHECK_INTERVAL = 4;
    /** 玩家proximity距离平方（~33米） */
    private static final int PROXIMITY_DISTANCE_SQ = 0x1C2000;
    /** 出生点最大检测范围 */
    private static final int PROXIMITY_LIMIT = 4096;

    @Autowired
    private MapManager mapManager;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AiEngine aiEngine;

    @Autowired
    private MonsterListMapper monsterListMapper;

    /** mapId → 该地图所有活着的怪物 */
    private final Map<Integer, List<Monster>> monstersByMap = new ConcurrentHashMap<>();
    /** 怪物名 → 模板 */
    private final Map<String, MonsterList> monsterTemplatesByName = new ConcurrentHashMap<>();
    /** 加权随机用：mapId → 累积权重数组 */
    private final Map<Integer, int[]> cumulativeWeightsByMap = new ConcurrentHashMap<>();
    /** 加权随机用：mapId → 对应怪物名列表 */
    private final Map<Integer, List<String>> monsterNamesByMap = new ConcurrentHashMap<>();
    /** 总tick计数器 */
    private long tickCounter = 0;
    /** 每张地图的总权重 */
    private final Map<Integer, Integer> totalWeightByMap = new ConcurrentHashMap<>();

    private final AtomicLong monsterIdGenerator = new AtomicLong(1);

    @PostConstruct
    public void init() {
        aiEngine.init();
        loadMonsterTemplates();
        buildSpawnTables();
        // 启动时不刷怪，等待玩家进入
        log.info("MonsterSpawnService initialized: {} monster types, 0 monsters spawned",
            monsterTemplatesByName.size());
    }

    private void loadMonsterTemplates() {
        List<MonsterList> templates = monsterListMapper.selectList(null);
        for (MonsterList t : templates) {
            if (t.getName() != null && !t.getName().isBlank()) {
                monsterTemplatesByName.put(t.getName().trim(), t);
            }
        }
    }

    /**
     * 构建加权随机表（从 mapmonster 配置）
     * 对每张地图，把 monster1/count1, monster2/count2... 构建成累积权重数组
     */
    private void buildSpawnTables() {
        for (Map.Entry<Integer, GameMap> entry : mapManager.getMaps().entrySet()) {
            int mapId = entry.getKey();
            GameMap gameMap = entry.getValue();
            MonsterSpawnConfig config = gameMap.getMonsterSpawnConfig();
            if (config == null || config.getWaves().isEmpty()) continue;

            List<String> names = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            int totalWeight = 0;

            for (MonsterWave wave : config.getWaves()) {
                if (monsterTemplatesByName.containsKey(wave.getMonsterName())) {
                    names.add(wave.getMonsterName());
                    totalWeight += wave.getCount();
                    weights.add(totalWeight);
                }
            }

            if (!names.isEmpty()) {
                monsterNamesByMap.put(mapId, names);
                cumulativeWeightsByMap.put(mapId, weights.stream().mapToInt(Integer::intValue).toArray());
                totalWeightByMap.put(mapId, totalWeight);
            }

            // 设置每个出生点的最大怪物数
            int maxPerPoint = config.getMaxMonsters() > 0
                ? Math.max(1, config.getMaxMonsters() / Math.max(1, gameMap.getSpawnPoints().size()))
                : 3;
            for (SpawnPoint sp : gameMap.getSpawnPoints()) {
                sp.setMaxMonsters(maxPerPoint);
            }
        }
    }

    // ======== 主 tick 循环 ========

    @Scheduled(fixedRate = 62) // ~16次/秒，与原版一致
    public void tick() {
        tickCounter++;

        for (Map.Entry<Integer, GameMap> entry : mapManager.getMaps().entrySet()) {
            int mapId = entry.getKey();
            GameMap gameMap = entry.getValue();
            MonsterSpawnConfig config = gameMap.getMonsterSpawnConfig();
            if (config == null) continue;

            int aliveCount = getAliveMonsterCount(mapId);

            // 频率控制（位掩码，与原版一致）
            int intervalMask = config.getInterval() > 0 ? (1 << config.getInterval()) - 1 : 3;
            if ((tickCounter & intervalMask) != 0) continue;

            // 刷新出生点的 active 状态
            updateSpawnPointActive(gameMap);

            // 低于上限才刷
            if (aliveCount >= config.getMaxMonsters()) continue;

            // 选择一个可以刷怪的出生点
            SpawnPoint targetPoint = selectSpawnPoint(gameMap);
            if (targetPoint == null) continue;

            // 加权随机选怪物类型
            String monsterName = pickRandomMonster(mapId);
            if (monsterName == null) continue;

            MonsterList template = monsterTemplatesByName.get(monsterName);
            if (template == null) continue;

            // 确定组队大小
            int groupSize = 1;
            if (template.getSpawnMin() != null && template.getSpawnMax() != null
                && template.getSpawnMax() > template.getSpawnMin()) {
                groupSize = ThreadLocalRandom.current().nextInt(
                    template.getSpawnMin(), template.getSpawnMax() + 1);
            }
            groupSize = Math.max(1, groupSize);

            // 刷怪
            for (int i = 0; i < groupSize; i++) {
                if (aliveCount >= config.getMaxMonsters()) break;
                if (!targetPoint.canSpawn()) break;

                Monster monster = createMonster(template, mapId, targetPoint);
                monstersByMap.computeIfAbsent(mapId, k -> new ArrayList<>()).add(monster);
                targetPoint.onMonsterSpawn();
                aliveCount++;
            }
        }

        // AI 更新 + 清理死亡怪物
        updateAndCleanup();
    }

    // ======== 出生点 active 状态更新 ========

    private void updateSpawnPointActive(GameMap gameMap) {
        // 先全部标记为 inactive
        for (SpawnPoint sp : gameMap.getSpawnPoints()) {
            sp.setActive(false);
        }

        // 遍历所有在线玩家，标记附近的出生点为 active
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (!session.isPlaying()) continue;

            for (SpawnPoint sp : gameMap.getSpawnPoints()) {
                int dx = sp.getX() - (int) session.getX();
                int dz = sp.getZ() - (int) session.getZ();
                int distSq = dx * dx + dz * dz;
                if (distSq < PROXIMITY_DISTANCE_SQ
                    && Math.abs(dx) < PROXIMITY_LIMIT
                    && Math.abs(dz) < PROXIMITY_LIMIT) {
                    sp.setActive(true);
                }
            }
        }
    }

    // ======== 出生点选择（三阶段） ========

    private SpawnPoint selectSpawnPoint(GameMap gameMap) {
        List<SpawnPoint> points = gameMap.getSpawnPoints();
        if (points.isEmpty()) return null;

        // 第一轮：active 且未超限
        List<SpawnPoint> candidates = new ArrayList<>();
        for (SpawnPoint sp : points) {
            if (sp.isActive() && sp.canSpawn()) {
                candidates.add(sp);
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }

        // 第二轮（简化版Boss/强制）：只要 active
        candidates.clear();
        for (SpawnPoint sp : points) {
            if (sp.isActive()) {
                candidates.add(sp);
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }

        return null;
    }

    // ======== 加权随机选怪物 ========

    private String pickRandomMonster(int mapId) {
        int[] cumulative = cumulativeWeightsByMap.get(mapId);
        List<String> names = monsterNamesByMap.get(mapId);
        Integer total = totalWeightByMap.get(mapId);
        if (cumulative == null || names == null || total == null || total == 0) return null;

        int rnd = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < cumulative.length; i++) {
            if (rnd < cumulative[i]) {
                return names.get(i);
            }
        }
        return names.get(names.size() - 1);
    }

    // ======== 创建怪物实例 ========

    private Monster createMonster(MonsterList template, int mapId, SpawnPoint point) {
        Monster monster = new Monster();
        monster.setId(monsterIdGenerator.getAndIncrement());
        monster.setTemplateId(template.getMonsterId() != null ? template.getMonsterId() : 0);
        monster.setName(template.getName());
        monster.setLevel(template.getLevel() != null ? template.getLevel() : 1);
        monster.setHp(template.getHp() != null ? template.getHp() : 1);
        monster.setMaxHp(template.getHp() != null ? template.getHp() : 1);
        monster.setAttack(template.getAtkPowMin() != null ? template.getAtkPowMin() : 1);
        monster.setDefense(template.getDefense() != null ? template.getDefense() : 0);
        monster.setSpeed(template.getMoveSpeed() != null ? template.getMoveSpeed() : 1);
        monster.setAttackRange(template.getAttackRange() != null ? template.getAttackRange() : 90);
        monster.setAbsorption(template.getAbsorb() != null ? template.getAbsorb() : 0);
        monster.setMapId(mapId);
        monster.setState(MonsterState.IDLE);
        monster.setLastTransTime(System.currentTimeMillis());

        // 在出生点附近随机偏移
        int offsetRange = point.getRange() > 0 ? point.getRange() : 200;
        monster.setX(point.getX() + ThreadLocalRandom.current().nextInt(offsetRange * 2) - offsetRange);
        monster.setY(point.getY());
        monster.setZ(point.getZ() + ThreadLocalRandom.current().nextInt(offsetRange * 2) - offsetRange);

        // 记录出生点（归位用）
        monster.setSpawnPointIndex(point.getId());
        monster.setSpawnX(point.getX());
        monster.setSpawnZ(point.getZ());

        return monster;
    }

    // ======== AI 更新 + 清理 ========

    private void updateAndCleanup() {
        for (Map.Entry<Integer, List<Monster>> entry : monstersByMap.entrySet()) {
            int mapId = entry.getKey();
            GameMap gameMap = mapManager.getMap(mapId);
            List<Monster> monsters = entry.getValue();

            for (Monster monster : monsters) {
                if (monster.isAlive()) {
                    aiEngine.update(monster);
                    // 更新 lastTransTime（有仇恨目标时）
                    if (monster.getTargetPlayerId() != null) {
                        monster.setLastTransTime(System.currentTimeMillis());
                    }
                }
            }

            // 清理死亡怪物（5分钟无玩家交互则移除）
            long now = System.currentTimeMillis();
            monsters.removeIf(m -> {
                if (!m.isAlive()) {
                    // 5分钟超时或死亡时间超过respawnTime → 从列表移除
                    if (now - m.getDeathTime() >= m.getRespawnTime()) {
                        // 归还出生点计数
                        findSpawnPoint(gameMap, m.getSpawnPointIndex())
                            .ifPresent(SpawnPoint::onMonsterDeath);
                        return true;
                    }
                }
                // 5分钟无玩家交互的活着怪物也移除（原版逻辑）
                if (m.isAlive() && now - m.getLastTransTime() > 5 * 60 * 1000) {
                    findSpawnPoint(gameMap, m.getSpawnPointIndex())
                        .ifPresent(SpawnPoint::onMonsterDeath);
                    return true;
                }
                return false;
            });
        }
    }

    private int getAliveMonsterCount(int mapId) {
        List<Monster> monsters = monstersByMap.get(mapId);
        if (monsters == null) return 0;
        int count = 0;
        for (Monster m : monsters) {
            if (m.isAlive()) count++;
        }
        return count;
    }

    private java.util.Optional<SpawnPoint> findSpawnPoint(GameMap gameMap, int pointId) {
        if (pointId < 0) return java.util.Optional.empty();
        return gameMap.getSpawnPoints().stream()
            .filter(sp -> sp.getId() == pointId)
            .findFirst();
    }

    // ======== 外部接口 ========

    public List<Monster> getMonstersByMap(int mapId) {
        return monstersByMap.getOrDefault(mapId, List.of());
    }

    public MonsterList getTemplate(String name) {
        return monsterTemplatesByName.get(name);
    }
}
