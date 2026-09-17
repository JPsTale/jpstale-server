package org.jpstale.server.game.entity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 实体运行时 ID 源(全局共享,跨 玩家/怪/地面物 统一命名空间)。
 *
 * 对齐原版 JPT `GetNewObjectSerial`(单服全局 ObjectSerialCnt):
 * 单进程内天然唯一;将来分片只需在生成器加 serverId 高位,改动收敛在此类。
 * 0 保留为空。
 */
public final class EntityIdSource {

    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private EntityIdSource() {
    }

    public static long nextId() {
        return SEQUENCE.getAndIncrement();
    }
}
