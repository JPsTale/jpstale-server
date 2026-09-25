package org.jpstale.server.game.model;

import lombok.Data;

/**
 * 怪物波次（某地图某种怪物的数量权重）
 */
@Data
public class MonsterWave {
    /** 该波次刷的怪物 = **monsterlist.id**（外键；表已从"按名字"迁移为按 id，2026-09-25）。 */
    private int monsterId;
    private int count;

    public MonsterWave() {}

    public MonsterWave(int monsterId, int count) {
        this.monsterId = monsterId;
        this.count = count;
    }
}