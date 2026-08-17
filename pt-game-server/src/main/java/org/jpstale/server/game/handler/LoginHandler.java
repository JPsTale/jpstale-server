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

import java.util.List;

/**
 * 登录请求处理器
 */
@Slf4j
@Component
@GamePacketHandler(MessageProto.ClientMessage.LOGIN_REQUEST_FIELD_NUMBER)
public class LoginHandler implements PacketHandler {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AccountService accountService;

    @Override
    public void handle(Channel channel, MessageProto.ClientMessage message) {
        MessageProto.C2S_LoginRequest request = message.getLoginRequest();
        PlayerSession session = sessionManager.getSession(channel);

        if (session == null) {
            log.error("No session found for channel: {}", channel.remoteAddress());
            return;
        }

        // 检查是否已登录
        if (session.isLoggedIn()) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.ALREADY_LOGIN, "Already logged in"));
            return;
        }

        String username = request.getUsername();
        String password = request.getPassword();

        log.info("Login attempt from account: {}", username);

        // 查找账号
        UserInfo user = accountService.findByUsername(username);
        if (user == null) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.INVALID_PASSWORD, "Invalid credentials"));
            return;
        }

        // 验证密码
        if (!accountService.verifyPassword(user, password)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.INVALID_PASSWORD, "Invalid credentials"));
            return;
        }

        // 检查封禁
        if (accountService.isBanned(user.getId())) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.ACCOUNT_BANNED, "Account is banned"));
            return;
        }

        // 检查是否已在线（顶号）
        PlayerSession existingSession = sessionManager.getSessionByAccountId(user.getId().longValue());
        if (existingSession != null && existingSession != session) {
            existingSession.send(MessageProto.ServerMessage.newBuilder()
                .setDisconnect(MessageProto.S2C_Disconnect.newBuilder()
                    .setReason("Account logged in from another location")
                    .build())
                .build());
            existingSession.close();
        }

        // 绑定账号
        session.setAccountId(user.getId().longValue());
        session.setLoggedIn(true);
        sessionManager.bindAccountId(channel, user.getId().longValue());

        // 获取角色列表
        List<CharacterInfo> characters = accountService.getCharacters(username);

        // 发送登录成功 + 角色列表
        MessageProto.S2C_LoginResponse.Builder responseBuilder = MessageProto.S2C_LoginResponse.newBuilder()
            .setSuccess(true)
            .setAccountId(user.getId());

        MessageProto.S2C_CharacterList.Builder characterListBuilder = MessageProto.S2C_CharacterList.newBuilder();
        for (CharacterInfo character : characters) {
            characterListBuilder.addCharacters(MessageProto.CharacterInfo.newBuilder()
                .setCharacterId(character.getId())
                .setName(character.getName())
                .setClassId(character.getJobCode() != null ? character.getJobCode() : 0)
                .setLevel(character.getLevel() != null ? character.getLevel() : 1)
                .setMapId(character.getLastStage() != null ? character.getLastStage() : 1)
                .setGold(character.getGold() != null ? character.getGold() : 0)
                .build());
        }

        session.send(MessageProto.ServerMessage.newBuilder()
            .setLoginResponse(responseBuilder.build())
            .build());

        session.send(MessageProto.ServerMessage.newBuilder()
            .setCharacterList(characterListBuilder.build())
            .build());

        log.info("Login successful for account: {}, characters: {}", username, characters.size());
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
