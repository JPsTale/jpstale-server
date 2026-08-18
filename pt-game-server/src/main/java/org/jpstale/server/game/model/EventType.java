package org.jpstale.server.game.model;

/**
 * 活动类型 — 对应原版 rsSERVER_CONFIG.Event_xxx
 */
public enum EventType {

    /** 硬核竞技场 (SoD) — 4队PK, 7波怪物 */
    HARDCORE("硬核竞技场", 15 * 60 * 1000, 60 * 60 * 1000),

    /** 区域刷怪活动 — FieldCode 42 */
    EVENT_SPAWN("区域刷怪", 15 * 60 * 1000, 60 * 60 * 1000),

    /** 巴别号角 — BOSS讨伐 */
    BABEL_HORN("巴别号角", 30 * 60 * 1000, 120 * 60 * 1000),

    /** 九尾狐 — BOSS讨伐 */
    NINE_FOX("九尾狐", 30 * 60 * 1000, 120 * 60 * 1000),

    /** 水晶收集 */
    CRYSTAL("水晶收集", 60 * 60 * 1000, 240 * 60 * 1000),

    /** 经验提升 */
    EXP_UP("经验提升", 60 * 60 * 1000, 240 * 60 * 1000),

    /** 季节活动 (通用) */
    SEASONAL("季节活动", 60 * 60 * 1000, 240 * 60 * 1000);

    private final String name;
    private final long playDuration;   // 活动持续时间 (毫秒)
    private final long cooldown;       // 冷却时间 (毫秒)

    EventType(String name, long playDuration, long cooldown) {
        this.name = name;
        this.playDuration = playDuration;
        this.cooldown = cooldown;
    }

    public String getName() { return name; }
    public long getPlayDuration() { return playDuration; }
    public long getCooldown() { return cooldown; }
}
