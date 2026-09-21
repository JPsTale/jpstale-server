package org.jpstale.common.mq;

import java.time.Instant;

/**
 * 游戏服注册表的**契约**（Redis key 与字段名）—— 写方与读方共用这一份。
 *
 * 写方 = game-server 的 `GameServerRegistration`（每 15s 心跳续期，TTL 30s）；
 * 读方 = web-server 的 `GameLoginController` 的选服列表。
 *
 * 为什么要有这个类：两边此前**各写一份字面量**（key 前缀 `pt:game:`、字段名 `id/name/ip/port/online`）。
 * 那种重复的失败方式是**静默**的 —— 改了一边忘另一边，编译不报错，症状是"列表少一台服"或某字段 null，
 * 得顺着 Redis 里的原始 JSON 才看得出来。收成一处之后，字段名就是 record 的组件名。
 *
 * 组件顺序即 Jackson 的序列化顺序，与旧的手写 `LinkedHashMap` 顺序一致 ⇒ 线上 JSON 不变。
 */
public record GameServerRegistry(int id, String name, String ip, int port, boolean online, long ts) {

    /** Redis key 前缀：`pt:game:<服务器 id>`。 */
    public static final String KEY_PREFIX = "pt:game:";

    /** 注册键 TTL（秒）。写方每 15s 心跳一次，故 30s 的含义是"两次心跳没到就算这台服掉了"。 */
    public static final long TTL_SECONDS = 30;

    public static String key(int serverId) {
        return KEY_PREFIX + serverId;
    }

    /** 扫描全部注册键用的模式（读方用）。 */
    public static String keyPattern() {
        return KEY_PREFIX + "*";
    }

    /** 心跳写入用：ts 取当前时刻、online 恒 true（掉线由 TTL 过期表达，不需要显式写 false）。 */
    public static GameServerRegistry online(int id, String name, String ip, int port) {
        return new GameServerRegistry(id, name, ip, port, true, Instant.now().toEpochMilli());
    }
}
