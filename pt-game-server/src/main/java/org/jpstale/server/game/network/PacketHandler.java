package org.jpstale.server.game.network;

import io.netty.channel.Channel;
import org.jpstale.server.proto.base.MessageProto;

/**
 * 消息处理器接口
 * 所有消息处理器都需要实现此接口
 */
@FunctionalInterface
public interface PacketHandler {

    /**
     * 处理消息
     *
     * @param channel 消息来源通道
     * @param message 客户端消息
     */
    void handle(Channel channel, MessageProto.ClientMessage message);
}
