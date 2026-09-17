package org.jpstale.server.game.model;

import lombok.Data;

/**
 * 怪物波次（某地图某种怪物的数量权重）
 */
@Data
public class MonsterWave {
    private String monsterName;
    private int count;

    public MonsterWave() {}

    public MonsterWave(String monsterName, int count) {
        this.monsterName = monsterName;
        this.count = count;
    }
}