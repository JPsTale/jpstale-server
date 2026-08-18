package org.jpstale.server.game.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏地图数据
 */
@Data
public class GameMap {
    private int id;
    private String name;
    private String shortName;
    private String typeMap;
    private int levelReq;
    private int pvp;
    private String stageFile;
    private List<SpawnPoint> spawnPoints = new ArrayList<>();
    private MonsterSpawnConfig monsterSpawnConfig;
}