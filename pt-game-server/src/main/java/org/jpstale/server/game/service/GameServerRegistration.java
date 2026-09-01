package org.jpstale.server.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class GameServerRegistration {

    private static final Logger log = LoggerFactory.getLogger(GameServerRegistration.class);
    private static final String KEY_PREFIX = "pt:game:";
    private static final long TTL_SECONDS = 30;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${pt.game.id:1}")
    private int serverId;

    @Value("${pt.game.name:Local Game Server}")
    private String serverName;

    @Value("${pt.game.external-ip:127.0.0.1}")
    private String externalIp;

    @Value("${pt.game.external-port:10007}")
    private int externalPort;

    public GameServerRegistration(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void register() {
        heartbeat();
        log.info("Game server registered: id={}, name={}:{}", serverId, serverName, externalIp + ":" + externalPort);
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", serverId);
            info.put("name", serverName);
            info.put("ip", externalIp);
            info.put("port", externalPort);
            info.put("online", true);
            info.put("ts", Instant.now().toEpochMilli());

            String key = KEY_PREFIX + serverId;
            redis.opsForValue().set(key, mapper.writeValueAsString(info), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to register game server", e);
        }
    }

    @PreDestroy
    public void deregister() {
        try {
            redis.delete(KEY_PREFIX + serverId);
            log.info("Game server deregistered: id={}", serverId);
        } catch (Exception e) {
            log.error("Failed to deregister game server", e);
        }
    }

    public int getServerId() { return serverId; }
    public String getServerName() { return serverName; }
    public String getExternalIp() { return externalIp; }
    public int getExternalPort() { return externalPort; }
}
