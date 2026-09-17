package org.jpstale.server.game.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图怪物刷怪配置
 */
@Data
public class MonsterSpawnConfig {
    private int maxMonsters;
    private int interval;
    private List<MonsterWave> waves = new ArrayList<>();
}