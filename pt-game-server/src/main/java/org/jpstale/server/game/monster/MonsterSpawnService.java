package org.jpstale.server.game.monster;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.ai.AiEngine;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.world.AOIManager;
import org.jpstale.server.game.world.MapManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
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
 * 管理怪物的生成、刷新
 */
@Slf4j
@Component
public class MonsterSpawnService {

    @Autowired
    private MapManager mapManager;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AiEngine aiEngine;

    private final Map<Integer, List<Monster>> monstersByMap = new ConcurrentHashMap<>();
    private final AtomicLong monsterIdGenerator = new AtomicLong(1);

    @PostConstruct
    public void init() {
        aiEngine.init();
        spawnDefaultMonsters();
        log.info("MonsterSpawnService initialized");
    }

    /**
     * 生成默认怪物
     */
    private void spawnDefaultMonsters() {
        // 在地图1生成10只怪物
        for (int i = 0; i < 10; i++) {
            spawnMonster(1, "Slime", 1, 100, 10, 5, 3.0f, 20, 10);
        }

        // 在地图2生成5只怪物
        for (int i = 0; i < 5; i++) {
            spawnMonster(2, "Goblin", 3, 200, 20, 10, 2.0f, 50, 30);
        }
    }

    /**
     * 生成怪物
     */
    public Monster spawnMonster(int mapId, String name, int level, int hp, int attack, int defense,
                               float speed, int exp, int gold) {
        Monster monster = new Monster();
        monster.setId(monsterIdGenerator.getAndIncrement());
        monster.setTemplateId(1);
        monster.setName(name);
        monster.setLevel(level);
        monster.setHp(hp);
        monster.setMaxHp(hp);
        monster.setAttack(attack);
        monster.setDefense(defense);
        monster.setSpeed(speed);
        monster.setExp(exp);
        monster.setGold(gold);
        monster.setMapId(mapId);
        monster.setState(MonsterState.IDLE);

        // 设置随机出生点
        MapManager.GameMap map = mapManager.getMap(mapId);
        if (map != null && map.getSpawnPoints() != null && !map.getSpawnPoints().isEmpty()) {
            List<MapManager.SpawnPoint> monsterSpawns = map.getSpawnPoints().stream()
                .filter(sp -> "monster".equals(sp.getType()))
                .toList();
            
            if (!monsterSpawns.isEmpty()) {
                MapManager.SpawnPoint spawn = monsterSpawns.get(ThreadLocalRandom.current().nextInt(monsterSpawns.size()));
                float offsetX = ThreadLocalRandom.current().nextFloat() * spawn.getRange() * 2 - spawn.getRange();
                float offsetZ = ThreadLocalRandom.current().nextFloat() * spawn.getRange() * 2 - spawn.getRange();
                monster.setX(spawn.getX() + offsetX);
                monster.setY(spawn.getY());
                monster.setZ(spawn.getZ() + offsetZ);
            } else {
                // 没有怪物出生点，使用地图中心
                monster.setX(map.getWidth() / 2.0f);
                monster.setY(0);
                monster.setZ(map.getHeight() / 2.0f);
            }
        } else {
            monster.setX(100);
            monster.setY(0);
            monster.setZ(100);
        }

        // 添加到地图
        monstersByMap.computeIfAbsent(mapId, k -> new ArrayList<>()).add(monster);

        log.info("Spawned monster: {} at ({}, {}, {}) on map {}", 
            name, monster.getX(), monster.getY(), monster.getZ(), mapId);

        return monster;
    }

    /**
     * 获取地图上的所有怪物
     */
    public List<Monster> getMonstersByMap(int mapId) {
        return monstersByMap.getOrDefault(mapId, List.of());
    }

    /**
     * 定时更新怪物 AI
     */
    @Scheduled(fixedRate = 1000) // 每秒更新一次
    public void updateMonsters() {
        for (Map.Entry<Integer, List<Monster>> entry : monstersByMap.entrySet()) {
            for (Monster monster : entry.getValue()) {
                if (monster.isAlive()) {
                    // 更新 AI
                    aiEngine.update(monster);

                    // 检查怪物是否应该攻击附近玩家
                    checkMonsterAggro(monster);
                } else {
                    // 检查是否应该刷新
                    checkRespawn(monster);
                }
            }
        }
    }

    /**
     * 检查怪物仇恨
     */
    private void checkMonsterAggro(Monster monster) {
        if (monster.getTargetPlayerId() != null) {
            return; // 已有目标
        }

        // 检查附近的玩家
        // TODO: 实现仇恨系统
    }

    /**
     * 检查怪物刷新
     */
    private void checkRespawn(Monster monster) {
        if (System.currentTimeMillis() - monster.getDeathTime() >= monster.getRespawnTime()) {
            // 刷新怪物
            monster.setHp(monster.getMaxHp());
            monster.setState(MonsterState.IDLE);
            monster.setTargetPlayerId(null);

            // 重新设置随机位置
            MapManager.GameMap map = mapManager.getMap(monster.getMapId());
            if (map != null) {
                monster.setX(ThreadLocalRandom.current().nextFloat() * map.getWidth());
                monster.setZ(ThreadLocalRandom.current().nextFloat() * map.getHeight());
            }

            log.info("Respawned monster: {} on map {}", monster.getName(), monster.getMapId());
        }
    }
}
