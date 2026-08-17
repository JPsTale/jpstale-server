package org.jpstale.server.game.handler;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.world.AOIManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 聊天处理器
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.CHAT_FIELD_NUMBER)
public class ChatHandler implements PacketHandler {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AOIManager aoiManager;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        MessageProto.C2S_Chat chatRequest = message.getChat();
        PlayerSession session = sessionManager.getSession(channel);

        if (session == null || !session.isPlaying()) {
            return;
        }

        String chatMessage = chatRequest.getMessage();
        if (chatMessage == null || chatMessage.isEmpty() || chatMessage.length() > 200) {
            return;
        }

        CommonProto.ChatChannel channel2 = chatRequest.getChannel();

        // 构建聊天消息
        MessageProto.S2C_Chat chatResponse = MessageProto.S2C_Chat.newBuilder()
            .setChannel(channel2)
            .setSenderId(session.getCharacterId())
            .setSenderName(session.getCharacterName() != null ? session.getCharacterName() : "")
            .setMessage(chatMessage)
            .setTimestamp(System.currentTimeMillis())
            .build();

        MessageProto.ServerMessage serverMessage = MessageProto.ServerMessage.newBuilder()
            .setChat(chatResponse)
            .build();

        switch (channel2) {
            case CHAT_WORLD:
                // 世界频道：发送给所有在线玩家
                broadcastToWorld(serverMessage);
                break;
            case CHAT_MAP:
                // 地图频道：发送给同地图玩家
                broadcastToMap(serverMessage);
                break;
            case CHAT_PRIVATE:
                // 私聊：发送给指定玩家
                sendPrivate(session, chatRequest.getTargetName(), serverMessage);
                break;
            default:
                log.warn("Unsupported chat channel: {}", channel2);
                break;
        }

        log.debug("Chat from {}: {}", session.getCharacterName(), chatMessage);
    }

    private void broadcastToWorld(MessageProto.ServerMessage message) {
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session.isPlaying()) {
                session.send(message);
            }
        }
    }

    private void broadcastToMap(MessageProto.ServerMessage message) {
        // TODO: 实现地图频道广播
        broadcastToWorld(message);
    }

    private void sendPrivate(PlayerSession sender, String targetName, MessageProto.ServerMessage message) {
        if (targetName == null || targetName.isEmpty()) {
            return;
        }

        PlayerSession targetSession = sessionManager.getSessionByCharacterName(targetName);
        if (targetSession == null) {
            // 目标不在线
            sender.send(MessageProto.ServerMessage.newBuilder()
                .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                    .setMessage("Player " + targetName + " is not online")
                    .setTimestamp(System.currentTimeMillis())
                    .build())
                .build());
            return;
        }

        targetSession.send(message);
    }
}
