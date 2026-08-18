package org.jpstale.server.game.network;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.stereotype.Component;

/**
 * Ping 消息处理
 * 处理客户端心跳（技术层）
 */
@Slf4j
@Component
public class PingService {

    /**
     * 报文入口：心跳
     */
    @GamePacketHandler(MessageProto.ClientMessage.PING_FIELD_NUMBER)
    public void handlePing(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_Ping ping = message.getPing();

        if (session != null) {
            // 回复 Pong
            MessageProto.S2C_Pong pong = MessageProto.S2C_Pong.newBuilder()
                .setTimestamp(ping.getTimestamp())
                .build();

            session.send(MessageProto.ServerMessage.newBuilder()
                .setPong(pong)
                .build());

            log.debug("Responded to ping from {}", session.getRemoteAddress());
        }
    }
}