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
     *
     * 为什么要单独有它：过去"尸体停留多久"与"这个刷新位多久补怪"是**同一个时钟** ——
     * 名额在"移除尸体"那一刻才释放，而移除发生在 `Monster.respawnTime`（30s）之后。
     * 尸体停留时长改为 `Monster.decayTime`（6s）后，若不加这个字段，名额会提前 24 秒释放
     * ⇒ **刷新速度静默变成 4 倍**。两者从此互相独立：表现（尸体躺多久）与玩法（多久补怪）。
     *
     * 口径：以**本点最后一次死亡**为起点（而不是每只怪各自计时）。这与原版的门控形状一致 ——
     * 原版是刷新点级的 `MonsterCount < LimitMax` + 刷新间隔，不是"每只怪一个冷却"。
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

    /**
     * 怪物被击杀：释放名额，并以**死亡时刻**（不是"尸体消失时刻"）起算刷新冷却。
     *
     * `deathTimeMs` 由调用方从 `Monster.getDeathTime()` 传入 —— 尸体要在 `decayTime` 之后
     * 才移除，若在这里取 `System.currentTimeMillis()`，冷却起点会晚 `decayTime`，
     * 等于把尸体停留时长又叠回刷新节奏里（正是本次要拆开的东西）。
     */
    public void onMonsterDeath(long deathTimeMs, int respawnMs) {
        monsterCount.decrementAndGet();
        lastDeathTime = deathTimeMs;
        if (respawnMs > 0) {
            respawnCooldownMs = respawnMs;
        }
    }

    /**
     * 怪物离场但**不是**死亡（D10 无玩家临近回收）：只释放名额，不启动击杀冷却 ——
     * 那不是玩家打死的，不该让刷新位额外等 30 秒。
     */
    public void onMonsterRemoved() {
        monsterCount.decrementAndGet();
    }
}
