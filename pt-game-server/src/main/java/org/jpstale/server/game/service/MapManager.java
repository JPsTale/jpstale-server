package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.MapList;
import org.jpstale.dao.gamedb.entity.MapMonster;
import org.jpstale.dao.gamedb.entity.MapSpawnPoint;
import org.jpstale.dao.gamedb.mapper.MapListMapper;
import org.jpstale.dao.gamedb.mapper.MapMonsterMapper;
import org.jpstale.dao.gamedb.mapper.MapSpawnPointMapper;
import org.jpstale.server.game.model.GameMap;
import org.jpstale.server.game.model.MonsterSpawnConfig;
import org.jpstale.server.game.model.MonsterWave;
import org.jpstale.server.game.model.SpawnPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图管理器
 * 从数据库 gamedb.maplist / gamedb.mapmonster / gamedb.mapspawnpoint 加载
 */
@Slf4j
@Component
public class MapManager {

    @Autowired
    private MapListMapper mapListMapper;

    @Autowired
    private MapMonsterMapper mapMonsterMapper;

    @Autowired
    private MapSpawnPointMapper mapSpawnPointMapper;

    private final Map<Integer, GameMap> maps = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadMapsFromDatabase();
        log.info("MapManager initialized with {} maps", maps.size());
    }

    private void loadMapsFromDatabase() {
        List<MapList> mapList = mapListMapper.selectList(null);
        List<MapMonster> allMonsters = mapMonsterMapper.selectList(null);
        List<MapSpawnPoint> allSpawnPoints = mapSpawnPointMapper.selectList(null);

        for (MapList ml : mapList) {
            GameMap gameMap = new GameMap();
            gameMap.setId(ml.getId());
            gameMap.setName(ml.getName());
            gameMap.setShortName(ml.getShortName());
            gameMap.setTypeMap(ml.getTypeMap());
            gameMap.setLevelReq(ml.getLevelReq() != null ? ml.getLevelReq() : 0);
            gameMap.setPvp(ml.getPvp() != null ? ml.getPvp() : 0);
            gameMap.setStageFile(ml.getStageFile());

            String mapIdStr = String.valueOf(ml.getId());

            // 加载怪物配置
            for (MapMonster mm : allMonsters) {
                if (mapIdStr.equals(mm.getStage())) {
                    MonsterSpawnConfig config = new MonsterSpawnConfig();
                    config.setMaxMonsters(mm.getMaxMonsters() != null ? mm.getMaxMonsters() : 0);
                    config.setInterval(mm.getInterval() != null ? mm.getInterval() : 0);
                    List<MonsterWave> waves = new ArrayList<>();
                    addWave(waves, mm.getMonster1(), mm.getCount1());
                    addWave(waves, mm.getMonster2(), mm.getCount2());
                    addWave(waves, mm.getMonster3(), mm.getCount3());
                    addWave(waves, mm.getMonster4(), mm.getCount4());
                    addWave(waves, mm.getMonster5(), mm.getCount5());
                    addWave(waves, mm.getMonster6(), mm.getCount6());
                    addWave(waves, mm.getMonster7(), mm.getCount7());
                    addWave(waves, mm.getMonster8(), mm.getCount8());
                    addWave(waves, mm.getMonster9(), mm.getCount9());
                    addWave(waves, mm.getMonster10(), mm.getCount10());
                    addWave(waves, mm.getMonster11(), mm.getCount11());
                    addWave(waves, mm.getMonster12(), mm.getCount12());
                    config.setWaves(waves);
                    gameMap.setMonsterSpawnConfig(config);
                    break;
                }
            }

            // 加载出生点 (mapspawnpoint.stage = maplist.id)
            List<SpawnPoint> spList = new ArrayList<>();
            for (MapSpawnPoint sp : allSpawnPoints) {
                if (sp.getStage() != null && sp.getStage() == ml.getId()) {
                    spList.add(new SpawnPoint(sp.getId(), sp.getDescription(),
                        sp.getX() != null ? sp.getX() : 0, 0,
                        sp.getZ() != null ? sp.getZ() : 0, 0));
                }
            }
            gameMap.setSpawnPoints(spList);

            maps.put(ml.getId(), gameMap);
        }
    }

    private void addWave(List<MonsterWave> waves, String monsterName, Integer count) {
        if (monsterName != null && !monsterName.isBlank() && count != null && count > 0) {
            waves.add(new MonsterWave(monsterName.trim(), count));
        }
    }

    public GameMap getMap(int mapId) {
        return maps.get(mapId);
    }

    public boolean isValidPosition(int mapId, float x, float z) {
        return maps.get(mapId) != null;
    }

    public Map<Integer, GameMap> getMaps() {
        return maps;
    }
}