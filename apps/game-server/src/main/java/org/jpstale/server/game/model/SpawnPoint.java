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

    /**
     * 击杀后的刷新冷却(ms)，从**死亡时刻**起算；以及最近一次死亡时刻。
     * 与 `Monster.decayTime`（尸体停留）是**两个独立的时钟**：否则"尸体躺多久"会顺带决定"多久补怪"。
     */
    private volatile long lastDeathTime;                      // 最近一次死亡时刻(ms)
    private volatile int respawnCooldownMs = 30000;           // 击杀后刷新冷却(ms)

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
        long now = System.currentTimeMillis();
        return active && monsterCount.get() < maxMonsters
            && now - lastSpawnTime >= cooldownMs
            && now - lastDeathTime >= respawnCooldownMs;
    }

    public void onMonsterSpawn() {
        monsterCount.incrementAndGet();
        lastSpawnTime = System.currentTimeMillis();
    }

    /** 怪物被击杀：释放名额并以**死亡时刻**起算刷新冷却（不能用"尸体消失时刻"，那会把两个时钟又叠在一起）。 */
    public void onMonsterDeath(long deathTimeMs, int respawnMs) {
        monsterCount.decrementAndGet();
        lastDeathTime = deathTimeMs;
        if (respawnMs > 0) {
            respawnCooldownMs = respawnMs;
        }
    }

    /** 非死亡的离场（无玩家临近回收）：只释放名额，不启动击杀冷却。 */
    public void onMonsterRemoved() {
        monsterCount.decrementAndGet();
    }
}
