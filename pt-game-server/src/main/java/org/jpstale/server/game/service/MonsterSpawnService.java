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
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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
    private MapRegionService mapRegionService;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AiEngine aiEngine;

    @Autowired
    private MovementService movementService;

    @Autowired
    private MonsterAOI monsterAOI;

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

    // ======== 主 tick 循环（由 GameServer.tick() 驱动，20 tick/s） ========

    public void tick(long currentTimeMillis) {
        tickCounter++;

        for (Map.Entry<Integer, GameMap> entry : mapManager.getMaps().entrySet()) {
            int mapId = entry.getKey();
            GameMap gameMap = entry.getValue();
            MonsterSpawnConfig config = gameMap.getMonsterSpawnConfig();
            if (config == null) continue;

            int aliveCount = getAliveMonsterCount(mapId);

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
        updateAndCleanup(currentTimeMillis);

        // 怪物 AOI：出现/消失/移动的可见性同步（覆盖新刷怪与玩家走动）
        monsterAOI.syncSessions();
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
        monster.setName(template.getName());
        monster.setLevel(template.getLevel() != null ? template.getLevel() : 1);
        monster.setHp(template.getHp() != null ? template.getHp() : 1);
        monster.setMaxHp(template.getHp() != null ? template.getHp() : 1);
        monster.setAttack(template.getAtkPowMin() != null ? template.getAtkPowMin() : 1);
        monster.setDefense(template.getDefense() != null ? template.getDefense() : 0);
        monster.setSpeed(template.getMoveSpeed() != null ? template.getMoveSpeed() : 1);
        monster.setAttackRange(template.getAttackRange() != null ? template.getAttackRange() : 90);
        monster.setAbsorption(template.getAbsorb() != null ? template.getAbsorb() : 0);
        monster.setViewsight(template.getViewSight() != null ? template.getViewSight() : 200);
        monster.setIntelligence(template.getInteligence() != null ? template.getInteligence() : 0);
        // 本性（原版 Nature）：Evil 主动攻击；Neutral/Normal 被动（受击反击）；Good 中立
        monster.setNature(natureOf(template.getMonsterType()));
        // 活动/归位范围：以视野 1.5 倍为界（原版 MoveRange，monsterlist 无此列）
        monster.setMoveRange(monster.getViewsight() * 1.5f);
        // 攻击间隔（毫秒）：对齐原版 GetAttackSpeedFrame —— frame = 80 + 10*clamp(attackSpeed-6,0,6)，按 60fps 换算
        monster.setAttackSpeed(attackIntervalMs(template.getAttackSpeed() != null ? template.getAttackSpeed() : 6));
        // 击杀经验：monsterlist.exp（单值数字字符串）
        monster.setExp(parseExp(template.getExp()));
        // 金币：按等级简单推导（后续接 dropitem 精确掉落）
        int lvl = template.getLevel() != null ? template.getLevel() : 1;
        monster.setGold(lvl * ThreadLocalRandom.current().nextInt(5, 15));
        monster.setMapId(mapId);
        monster.setState(MonsterState.IDLE);
        monster.setLastTransTime(System.currentTimeMillis());
        // 客户端渲染资源路径：DB modelfile（如 char\monster\Monimp\Monimp-a.INI）→
        // 规范化为磁盘实际小写 .inx 路径（Linux 大小写敏感）。
        monster.setModelFile(normalizeModelPath(template.getModelFile()));

        // 在出生点附近随机偏移
        int offsetRange = point.getRange() > 0 ? point.getRange() : 200;
        monster.setX(point.getX() + ThreadLocalRandom.current().nextInt(offsetRange * 2) - offsetRange);
        monster.setZ(point.getZ() + ThreadLocalRandom.current().nextInt(offsetRange * 2) - offsetRange);
        // Y 权威用地形高度（怪物视野高度差判定用，SpawnPoint 无 Y）
        monster.setY(mapRegionService.getHeight(mapId, monster.getX(), monster.getZ()));

        // 记录出生点（归位用）
        monster.setSpawnPointIndex(point.getId());
        monster.setSpawnX(point.getX());
        monster.setSpawnZ(point.getZ());

        return monster;
    }

    /**
     * 规范化 DB modelfile → 客户端 .inx 资源路径：
     * 反斜杠→斜杠、转小写（Linux 大小写敏感）、去扩展名后统一补 .inx。
     * 例：char\monster\Monimp\Monimp-a.INI → char/monster/monimp/monimp-a.inx
     */
    private static String normalizeModelPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace('\\', '/').trim().toLowerCase();
        int slash = s.lastIndexOf('/');
        String dir = slash >= 0 ? s.substring(0, slash) : "";
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String base = dir.isEmpty() ? name : dir + "/" + name;
        return base + ".inx";
    }

    private static int parseExp(String exp) {
        if (exp == null || exp.isBlank()) {
            return 0;
        }
        try {
            // "105" / "1000" 单值；形如 "1 10" 时取第一个
            return Integer.parseInt(exp.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int natureOf(String monsterType) {
        if (monsterType == null) return 0;
        String t = monsterType.trim();
        if (t.equalsIgnoreCase("Evil")) return 1;
        if (t.equalsIgnoreCase("Good")) return 2;
        return 0; // Neutral / Normal → 被动
    }

    /**
     * 攻击间隔毫秒（对齐原版 GetAttackSpeedFrame）：
     * frame = 80 + 10*clamp(attackSpeed-6, 0, 6)，按 60fps 换算。
     * attackSpeed 7-8 → 90-100 帧 → 1.5-1.67s
     */
    private static long attackIntervalMs(int attackSpeed) {
        int cnt = Math.max(0, Math.min(6, attackSpeed - 6));
        int frames = 80 + 10 * cnt;
        return Math.round(frames * 1000.0 / 60.0);
    }

    // ======== AI 更新 + 清理 ========

    private void updateAndCleanup(long now) {
        for (Map.Entry<Integer, List<Monster>> entry : monstersByMap.entrySet()) {
            int mapId = entry.getKey();
            GameMap gameMap = mapManager.getMap(mapId);
            List<Monster> monsters = entry.getValue();

            for (Monster monster : monsters) {
                if (monster.isAlive()) {
                    // i%5 错峰：每 tick 只处理 1/5 怪物(AI+移动)，5 tick 轮完。
                    // 20FPS 下每轮间隔 250ms，等效原版 16FPS 每 4 tick 一轮的 AI 更新率。
                    if ((monster.getId() % 5) == (tickCounter % 5)) {
                        // AI 决策（设状态+目标）
                        aiEngine.update(monster);
                        // 移动执行（根据状态更新位置）
                        movementService.updateMonster(monster);
                        // 位置/动画变化 → 广播给观察者
                        monsterAOI.broadcastMove(monster);
                        // 更新 lastTransTime（有仇恨目标时）
                        if (monster.getTargetPlayerId() != null) {
                            monster.setLastTransTime(now);
                        }
                    }
                }
            }

            // 清理死亡怪物（5分钟无玩家交互则移除）
            monsters.removeIf(m -> {
                if (!m.isAlive()) {
                    // 5分钟超时或死亡时间超过respawnTime → 从列表移除
                    if (now - m.getDeathTime() >= m.getRespawnTime()) {
                        // 通知观察者消失 + 归还出生点计数
                        monsterAOI.onMonsterRemoved(m);
                        findSpawnPoint(gameMap, m.getSpawnPointIndex())
                            .ifPresent(SpawnPoint::onMonsterDeath);
                        return true;
                    }
                }
                // 5分钟无玩家交互的活着怪物也移除（原版逻辑）
                if (m.isAlive() && now - m.getLastTransTime() > 5 * 60 * 1000) {
                    monsterAOI.onMonsterRemoved(m);
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
