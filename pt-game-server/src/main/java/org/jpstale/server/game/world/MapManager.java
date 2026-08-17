package org.jpstale.server.game.world;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图管理器
 * 管理所有地图的配置和状态
 */
@Slf4j
@Component
public class MapManager {

    private final Map<Integer, GameMap> maps = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 加载地图配置
        // TODO: 从数据库或配置文件加载
        loadDefaultMaps();
        log.info("MapManager initialized with {} maps", maps.size());
    }

    private void loadDefaultMaps() {
        // 默认地图配置（后续从数据库加载）
        GameMap map1 = new GameMap();
        map1.setId(1);
        map1.setName("Ruchon Beach");
        map1.setWidth(2048);
        map1.setHeight(2048);
        List<SpawnPoint> spawns1 = new ArrayList<>();
        spawns1.add(new SpawnPoint(1, "player", 1024.0f, 0.0f, 1024.0f, 50.0f));
        spawns1.add(new SpawnPoint(2, "monster", 800.0f, 0.0f, 800.0f, 200.0f));
        map1.setSpawnPoints(spawns1);
        maps.put(1, map1);

        GameMap map2 = new GameMap();
        map2.setId(2);
        map2.setName("Bless Castle");
        map2.setWidth(4096);
        map2.setHeight(4096);
        List<SpawnPoint> spawns2 = new ArrayList<>();
        spawns2.add(new SpawnPoint(3, "player", 2048.0f, 0.0f, 2048.0f, 100.0f));
        map2.setSpawnPoints(spawns2);
        maps.put(2, map2);
    }

    /**
     * 获取地图
     */
    public GameMap getMap(int mapId) {
        return maps.get(mapId);
    }

    /**
     * 检查位置是否有效
     */
    public boolean isValidPosition(int mapId, float x, float z) {
        GameMap map = maps.get(mapId);
        if (map == null) return false;

        // 检查坐标是否在地图范围内
        if (x < 0 || x > map.getWidth() || z < 0 || z > map.getHeight()) {
            return false;
        }

        // TODO: 检查碰撞区域
        return true;
    }

    /**
     * 地图数据
     */
    @Data
    public static class GameMap {
        private int id;
        private String name;
        private int width;
        private int height;
        private List<SpawnPoint> spawnPoints;
    }

    /**
     * 出生点
     */
    @Data
    public static class SpawnPoint {
        private int id;
        private String type;
        private float x;
        private float y;
        private float z;
        private float range;

        public SpawnPoint() {}

        public SpawnPoint(int id, String type, float x, float y, float z, float range) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.range = range;
        }
    }
}
