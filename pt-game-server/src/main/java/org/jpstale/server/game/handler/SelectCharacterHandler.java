package org.jpstale.server.game.handler;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.service.AccountService;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 选择角色处理器
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.SELECT_CHARACTER_FIELD_NUMBER)
public class SelectCharacterHandler implements PacketHandler {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AccountService accountService;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        MessageProto.C2S_SelectCharacter request = message.getSelectCharacter();
        PlayerSession session = sessionManager.getSession(channel);

        if (session == null || !session.isLoggedIn()) {
            return;
        }

        long characterId = request.getCharacterId();

        // 获取账号名
        String accountName = getAccountName(session);
        if (accountName == null) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.NOT_LOGIN, "Not logged in"));
            return;
        }

        // 验证角色归属
        if (!accountService.isCharacterOwned(accountName, (int) characterId)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.CHARACTER_NOT_FOUND, "Character not found"));
            return;
        }

        // 获取角色信息
        CharacterInfo character = accountService.getCharacterById((int) characterId);
        if (character == null) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.CHARACTER_NOT_FOUND, "Character not found"));
            return;
        }

        // 绑定角色到 Session
        session.setCharacterId(characterId);
        session.setCharacterName(character.getName());
        session.setPlaying(true);
        sessionManager.bindCharacterId(channel, characterId, character.getName());

        // 发送角色状态
        session.send(MessageProto.ServerMessage.newBuilder()
            .setPlayerState(MessageProto.S2C_PlayerState.newBuilder()
                .setPlayerId(characterId)
                .setMapId(character.getLastStage() != null ? character.getLastStage() : 1)
                .setHp(100) // 默认HP
                .setMp(50)  // 默认MP
                .setMaxHp(100)
                .setMaxMp(50)
                .setLevel(character.getLevel() != null ? character.getLevel() : 1)
                .setGold(character.getGold() != null ? character.getGold() : 0)
                .setExp(character.getExperience() != null ? character.getExperience() : 0)
                .build())
            .build());

        log.info("Character selected: {} ({}) for account: {}", 
            character.getName(), characterId, accountName);
    }

    private String getAccountName(PlayerSession session) {
        if (session.getAccountId() != null) {
            var user = accountService.findById(session.getAccountId().intValue());
            return user != null ? user.getAccountName() : null;
        }
        return null;
    }

    private MessageProto.ServerMessage buildErrorResponse(CommonProto.ErrorCode errorCode, String message) {
        return MessageProto.ServerMessage.newBuilder()
            .setError(MessageProto.S2C_Error.newBuilder()
                .setErrorCode(errorCode)
                .setErrorMessage(message)
                .build())
            .build();
    }
}
