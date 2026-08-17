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
 * 登出处理器
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.LOGOUT_FIELD_NUMBER)
public class LogoutHandler implements PacketHandler {

    @Autowired
    private SessionManager sessionManager;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        PlayerSession session = sessionManager.getSession(channel);

        if (session == null) {
            return;
        }

        String characterName = session.getCharacterName();
        
        // TODO: 保存角色数据
        // playerSaveService.saveOnLogout(session);

        // 清除角色绑定
        session.setCharacterId(null);
        session.setCharacterName(null);
        session.setPlaying(false);

        log.info("Character logged out: {}", characterName);
    }
}
