package org.jpstale.server.game.model;

import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地图出生点
 */
@Data
public class SpawnPoint {
    private int id;
    private String description;
    private int x;
    private int y;
    private int z;
    private int range;

    // 运行时状态
    private volatile boolean active;                          // 附近是否有玩家
    private final AtomicInteger monsterCount = new AtomicInteger(0); // 当前怪物数
    private volatile int maxMonsters = 3;                     // 本点怪物上限
    private volatile long lastSpawnTime;                      // 上次刷怪时间(ms)
    private volatile int cooldownMs = 1000;                   // 刷怪冷却(ms)

    public SpawnPoint() {}

    public SpawnPoint(int id, String description, int x, int y, int z, int range) {
        this.id = id;
        this.description = description;
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = range;
    }

    public boolean canSpawn() {
        return active && monsterCount.get() < maxMonsters
            && System.currentTimeMillis() - lastSpawnTime >= cooldownMs;
    }

    public void onMonsterSpawn() {
        monsterCount.incrementAndGet();
        lastSpawnTime = System.currentTimeMillis();
    }

    public void onMonsterDeath() {
        monsterCount.decrementAndGet();
    }
}