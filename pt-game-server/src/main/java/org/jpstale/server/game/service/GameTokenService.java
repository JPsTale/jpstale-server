package org.jpstale.server.game.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GameTokenService {

    private static final Logger log = LoggerFactory.getLogger(GameTokenService.class);

    private final StringRedisTemplate redis;

    public GameTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Validate a sa-token token by reading directly from Redis.
     * Key pattern: satoken:login:token:{token} → loginId (accountId)
     */
    public Long validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String key = "satoken:login:token:" + token;
            String value = redis.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                log.debug("Token not found in Redis: {}", token);
                return null;
            }
            return Long.parseLong(value);
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return null;
        }
    }
}
