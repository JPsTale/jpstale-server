package org.jpstale.server.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
public class RedisMsgProducer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final String topicName;

    public RedisMsgProducer(RedisTemplate<String, Object> redisTemplate, String topicName) {
        this.redisTemplate = redisTemplate;
        this.topicName = topicName;
    }

    public void sendMessage(CommonMsg message) {
        log.debug("Publishing Redis message: type={}, requestId={}", message.getType(), message.getRequestId());
        redisTemplate.convertAndSend(topicName, message);
    }
}
