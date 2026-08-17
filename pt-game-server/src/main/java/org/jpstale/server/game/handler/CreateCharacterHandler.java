package org.jpstale.server.game.handler;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.service.AccountService;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 创建角色处理器
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.CREATE_CHARACTER_FIELD_NUMBER)
public class CreateCharacterHandler implements PacketHandler {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9]{2,12}$");
    private static final int MAX_CHARACTERS = 4;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AccountService accountService;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        MessageProto.C2S_CreateCharacter request = message.getCreateCharacter();
        PlayerSession session = sessionManager.getSession(channel);

        if (session == null || !session.isLoggedIn()) {
            return;
        }

        String name = request.getName();
        int classId = request.getClassId();

        // 验证角色名称
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.INVALID_NAME, "Invalid character name"));
            return;
        }

        // 检查名称是否已存在
        if (accountService.isCharacterNameExists(name)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.NAME_EXISTS, "Character name already exists"));
            return;
        }

        // 检查角色数量限制
        String accountName = getAccountName(session);
        long characterCount = accountService.getCharacterCount(accountName);
        if (characterCount >= MAX_CHARACTERS) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.CHARACTER_LIMIT, "Character limit reached"));
            return;
        }

        // 创建角色
        CharacterInfo character = accountService.createCharacter(accountName, name, classId);

        // 发送创建成功
        session.send(MessageProto.ServerMessage.newBuilder()
            .setCreateCharacterResult(MessageProto.S2C_CreateCharacterResult.newBuilder()
                .setSuccess(true)
                .setCharacterId(character.getId())
                .build())
            .build());

        log.info("Character created: {} for account: {}", name, accountName);
    }

    private String getAccountName(PlayerSession session) {
        // 从数据库查询账号名
        if (session.getAccountId() != null) {
            UserInfo user = accountService.findById(session.getAccountId().intValue());
            return user != null ? user.getAccountName() : "unknown";
        }
        return "unknown";
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
