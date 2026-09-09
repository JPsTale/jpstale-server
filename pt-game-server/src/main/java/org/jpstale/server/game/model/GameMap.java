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

    /** 安全区（村庄）：gamedb.maplist.typemap='Cities'。客户端据此禁战斗姿态（收武器动画）。 */
    public boolean isSafe() {
        return "Cities".equalsIgnoreCase(typeMap);
    }
}