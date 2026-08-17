package org.jpstale.server.game.handler;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ping 消息处理器
 * 处理客户端心跳
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.PING_FIELD_NUMBER)
public class PingHandler implements PacketHandler {

    @Autowired
    private SessionManager sessionManager;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        MessageProto.C2S_Ping ping = message.getPing();
        PlayerSession session = sessionManager.getSession(channel);

        if (session != null) {
            // 回复 Pong
            MessageProto.S2C_Pong pong = MessageProto.S2C_Pong.newBuilder()
                .setTimestamp(ping.getTimestamp())
                .build();

            session.send(MessageProto.ServerMessage.newBuilder()
                .setPong(pong)
                .build());

            log.debug("Responded to ping from {}", channel.remoteAddress());
        }
    }
}
