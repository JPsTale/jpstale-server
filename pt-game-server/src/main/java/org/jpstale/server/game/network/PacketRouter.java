package org.jpstale.server.game.network;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息路由器
 * 根据消息类型分发到对应的 Handler
 */
@Slf4j
@Component
public class PacketRouter {

    private final Map<Integer, PacketHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 注册消息处理器
     */
    public void register(int messageType, PacketHandler handler) {
        handlers.put(messageType, handler);
        log.debug("Registered handler for message type: {}", messageType);
    }

    /**
     * 路由消息到对应的处理器
     */
    public void route(Channel channel, MessageProto.ClientMessage message) {
        int messageType = message.getPayloadCase().getNumber();
        PacketHandler handler = handlers.get(messageType);

        if (handler != null) {
            try {
                handler.handle(channel, message);
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
