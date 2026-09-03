package org.jpstale.server.game.network;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 断线重连管理器
 * 管理断线玩家的重连 Token
 */
@Slf4j
@Component
public class ReconnectionManager {

    private final Map<String, PendingReconnection> pendingReconnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * 生成重连 Token
     */
    public String generateReconnectToken(PlayerSession session) {
        String token = UUID.randomUUID().toString();
        PendingReconnection pending = new PendingReconnection(
            session.getAccountId(),
            session.getCharacterId(),
            session.getCharacterName(),
            session.getCurrentMapId(),
            session.getX(),
            session.getY(),
            session.getZ(),
            session.getHp(),
            session.getMaxHp(),
            session.getMp(),
            session.getMaxMp(),
            session.getLevel(),
            System.currentTimeMillis()
        );

        pendingReconnections.put(token, pending);

        // 5分钟后过期
        scheduler.schedule(() -> {
            PendingReconnection removed = pendingReconnections.remove(token);
            if (removed != null) {
                log.info("Reconnect token expired for character: {}", removed.characterName);
            }
        }, 5, TimeUnit.MINUTES);

        log.info("Generated reconnect token for character: {}", session.getCharacterName());
        return token;
    }

    /**
     * 验证重连 Token
     */
    public PendingReconnection validateReconnectToken(String token) {
        return pendingReconnections.remove(token);
    }

    /**
     * 待重连数据
     */
    public record PendingReconnection(
        Long accountId,
        Long characterId,
        String characterName,
        int mapId,
        double x,
        double y,
        double z,
        int hp,
        int maxHp,
        int mp,
        int maxMp,
        int level,
        long disconnectTime
    ) {}
}
