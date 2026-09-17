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

    /**
     * Revoke a sa-token (大退/退出登录)：删除 token→loginId 映射与对应 session，
     * 使该 token 立即失效，同 token 无法再连接选角；账号需重新登录换取新 token。
     */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            String tokenKey = "satoken:login:token:" + token;
            Long logoutToken = null;
            try {
                logoutToken = Long.parseLong(redis.opsForValue().get(tokenKey));
            } catch (Exception e) {
                logoutToken = null;
            }
            redis.delete(tokenKey);
            if (logoutToken != null) {
                redis.delete("satoken:login:session:" + logoutToken);
            }
            log.info("Token revoked: {} (account={})", token, logoutToken);
        } catch (Exception e) {
            log.warn("Token revoke failed: {}", e.getMessage());
        }
    }
}
