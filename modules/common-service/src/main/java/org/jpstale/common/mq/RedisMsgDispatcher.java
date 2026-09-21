package org.jpstale.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RedisMsgDispatcher implements MessageListener, ApplicationContextAware {

    private final Map<String, List<RedisMsgListener>> listenerMap = new HashMap<>();

    private final GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
            .build();

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        CommonMsg msg;
        try {
            msg = serializer.deserialize(message.getBody(), CommonMsg.class);
        } catch (Exception e) {
            log.error("Failed to deserialize Redis message", e);
            return;
        }
        if (msg == null || msg.getType() == null || msg.getType().isBlank()) {
            log.debug("Empty or typeless Redis message ignored");
            return;
        }
        List<RedisMsgListener> listeners = listenerMap.get(msg.getType());
        if (listeners == null || listeners.isEmpty()) {
            log.debug("No listener for type: {}", msg.getType());
            return;
        }
        for (RedisMsgListener listener : listeners) {
            try {
                listener.onMessage(msg);
            } catch (Exception e) {
                log.error("Listener error for type: {}", msg.getType(), e);
            }
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        Map<String, RedisMsgListener> beans = ctx.getBeansOfType(RedisMsgListener.class);
        for (RedisMsgListener listener : beans.values()) {
            listenerMap.computeIfAbsent(listener.getType(), k -> new ArrayList<>()).add(listener);
        }
        log.info("RedisMsgDispatcher initialized, listeners: {}", listenerMap.keySet());
    }
}
