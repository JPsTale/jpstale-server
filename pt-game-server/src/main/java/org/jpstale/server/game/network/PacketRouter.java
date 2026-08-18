package org.jpstale.server.game.network;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息路由器
 * 根据消息类型分发到对应的处理 bean（Service 类）与报文入口方法
 */
@Slf4j
@Component
public class PacketRouter {

    /**
     * 报文入口：bean 实例 + 方法
     */
    public record HandlerEntry(Object bean, java.lang.reflect.Method method) {
    }

    private final Map<Integer, HandlerEntry> handlers = new ConcurrentHashMap<>();

    /**
     * 注册消息处理器
     */
    public void register(int messageType, Object bean, java.lang.reflect.Method method) {
        handlers.put(messageType, new HandlerEntry(bean, method));
        log.debug("Registered handler for message type: {}", messageType);
    }

    /**
     * 路由消息到对应的处理器
     */
    public void route(PlayerSession session, MessageProto.ClientMessage message) {
        int messageType = message.getPayloadCase().getNumber();
        HandlerEntry entry = handlers.get(messageType);

        if (entry != null) {
            try {
                entry.method().invoke(entry.bean(), session, message);
            } catch (Exception e) {
                log.error("Error handling message type: {}", messageType, e);
            }
        } else {
            log.warn("No handler registered for message type: {}", messageType);
        }
    }

    /**
     * 获取已注册的处理器数量
     */
    public int getHandlerCount() {
        return handlers.size();
    }
}