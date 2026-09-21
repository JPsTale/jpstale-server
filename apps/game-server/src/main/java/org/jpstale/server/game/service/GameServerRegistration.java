package org.jpstale.server.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.jpstale.common.mq.GameServerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class GameServerRegistration {

    private static final Logger log = LoggerFactory.getLogger(GameServerRegistration.class);

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

    /** 心跳：写入 {@link GameServerRegistry} 契约的 JSON（key 前缀/TTL/字段名都取那一处定义）。 */
    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        try {
            GameServerRegistry info = GameServerRegistry.online(serverId, serverName, externalIp, externalPort);
            redis.opsForValue().set(GameServerRegistry.key(serverId), mapper.writeValueAsString(info),
                    GameServerRegistry.TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to register game server", e);
        }
    }

    /**
     * 用 {@link ContextClosedEvent} 而不是 {@code @PreDestroy}：
     * {@code AbstractApplicationContext.doClose()} 的顺序是
     * ① 发布 ContextClosedEvent → ② {@code lifecycleProcessor.onClose()}（停掉 SmartLifecycle，
     * 含 LettuceConnectionFactory）→ ③ {@code destroyBeans()}（这时才跑 {@code @PreDestroy}）。
     * 所以 @PreDestroy 里 Redis 已经 STOPPED，delete 必然抛
     * "LettuceConnectionFactory has been STOPPED"（每次关服都留一条像崩溃的 ERROR 栈）。
     */
    @EventListener(ContextClosedEvent.class)
    public void deregister() {
        try {
            redis.delete(GameServerRegistry.key(serverId));
            log.info("Game server deregistered: id={}", serverId);
        } catch (Exception e) {
            // 关服时 Redis 恰好不可用（先停了 Redis / 断网）不算故障：注册键靠 TTL 自然过期。
            log.warn("Failed to deregister game server (key expires in {}s): {}",
                    GameServerRegistry.TTL_SECONDS, e.toString());
        }
    }

    public int getServerId() { return serverId; }
    public String getServerName() { return serverName; }
    public String getExternalIp() { return externalIp; }
    public int getExternalPort() { return externalPort; }
}
