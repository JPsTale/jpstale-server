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

    /** 全部地图（安全区表下发用） */
    public java.util.Collection<org.jpstale.server.game.model.GameMap> allMaps() {
        return maps.values();
    }

    public boolean isValidPosition(int mapId, float x, float z) {
        return maps.get(mapId) != null;
    }

    public Map<Integer, GameMap> getMaps() {
        return maps;
    }

    // ======== 地图进入门槛（数据来自 gamedb.maplist.levelreq，原版 FieldLimitLevel_Table 的同源列） ========

    /**
     * `levelreq` 达到该值 = 该图未开放（原版用 1000 表示锁死；11 职业库的新图用 150/110 等，
     * 而 PT 满级约 148 → 一律按"等级永远够不到"处理，报"未开放"而不是"需要 150 级"）。
     */
    private static final int MAP_LEVEL_LOCKED = 150;

    /** 拒绝原因（调用方据此给玩家**可见**提示，不许静默） */
    public enum EnterDeny {
        OK, LEVEL_TOO_LOW, NOT_OPEN, NO_SUCH_MAP
    }

    /**
     * 玩家能否进入某图 —— **全服唯一的门槛判定**。
     * 原版语义是硬门槛（`WingWarpGate_Field`: `if (FieldLimitLevel_Table[code] > Level) return FALSE`），
     * 我们照此实现：`levelreq <= level` 才放行。
     *
     * 调用点（别在各处各写一份）：跨图边界、传送（`TeleportService.teleport`）、传送门、NPC 对话传送。
     */
    public EnterDeny canEnter(int level, int mapId) {
        GameMap m = maps.get(mapId);
        if (m == null) {
            return EnterDeny.NO_SUCH_MAP;
        }
        int req = m.getLevelReq();
        if (req >= MAP_LEVEL_LOCKED) {
            return EnterDeny.NOT_OPEN;
        }
        return level >= req ? EnterDeny.OK : EnterDeny.LEVEL_TOO_LOW;
    }

    /** 该图的等级要求（0 = 无门槛）。给提示文案用 */
    public int levelReqOf(int mapId) {
        GameMap m = maps.get(mapId);
        return m != null ? m.getLevelReq() : 0;
    }
}