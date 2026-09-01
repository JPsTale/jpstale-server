package org.jpstale.server.game.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.common.ValidationInterceptor;
import org.jpstale.server.game.common.ValidationResult;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.GameTokenService;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Netty 消息处理器
 * 将接收到的消息转发给 PacketRouter
 */
@Slf4j
@Component
@Sharable
public class PacketRouterHandler extends SimpleChannelInboundHandler<MessageProto.ClientMessage> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private PacketRouter packetRouter;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ValidationInterceptor validationInterceptor;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private ReconnectionManager reconnectionManager;

    @Autowired
    private org.jpstale.server.game.service.PlayerService playerService;

    @Autowired
    private org.jpstale.server.game.service.AccountService accountService;

    @Autowired
    private GameTokenService gameTokenService;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof TextWebSocketFrame textFrame) {
            handleTokenAuth(ctx, textFrame.text());
            return;
        }
        super.channelRead(ctx, msg);
    }

    private void handleTokenAuth(ChannelHandlerContext ctx, String text) {
        try {
            JsonNode json = MAPPER.readTree(text);
            String type = json.has("type") ? json.get("type").asText() : "";
            if (!"auth.token".equals(type)) {
                log.warn("Unexpected first message type: {}", type);
                ctx.close();
                return;
            }
            String token = json.has("token") ? json.get("token").asText() : null;
            if (token == null || token.isBlank()) {
                log.warn("Missing token in auth.token message");
                ctx.close();
                return;
            }
            Long accountId = gameTokenService.validate(token);
            if (accountId == null) {
                log.warn("Invalid token: {}", token);
                ctx.close();
                return;
            }
            PlayerSession session = sessionManager.getSession(ctx.channel());
            if (session == null) {
                session = sessionManager.createSession(ctx.channel());
            }
            session.setAccountId(accountId);
            session.setState(SessionState.SERVER_SELECTED);
            sessionManager.bindAccountId(ctx.channel(), accountId);
            log.info("Token auth OK: accountId={}, remote={}", accountId, ctx.channel().remoteAddress());
            accountService.sendCharacterList(session);
        } catch (Exception e) {
            log.error("Token auth failed", e);
            ctx.close();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageProto.ClientMessage msg) throws Exception {
        PlayerSession session = sessionManager.getSession(ctx.channel());

        // 输入验证
        if (session != null && session.isPlaying()) {
            ValidationResult result = validationInterceptor.validate(session, msg);
            if (!result.isValid()) {
                log.warn("Validation failed for player {}: {}", 
                    session.getCharacterName(), result.getErrorMessage());
                
                session.send(MessageProto.ServerMessage.newBuilder()
                    .setError(MessageProto.S2C_Error.newBuilder()
                        .setErrorCode(result.getErrorCode())
                        .setErrorMessage(result.getErrorMessage())
                        .build())
                    .build());
                return;
            }
        }

        packetRouter.route(session, msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("Client connected: {}", ctx.channel().remoteAddress());
        sessionManager.createSession(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("Client disconnected: {}", ctx.channel().remoteAddress());
        // 移除 Session
        PlayerSession session = sessionManager.getSession(ctx.channel());
        if (session != null) {
            if (session.isPlaying() && session.isAllowReconnect() && !session.isReconnectTokenIssued()) {
                // 断线重连兜底：READER_IDLE 未触发（如客户端直接断网）时，生成 token 供 5 分钟内重连
                reconnectionManager.generateReconnectToken(session);
            }
            aoiManager.removePlayer(session);
            // 清理玩家缓存（重登时重新权威加载）
            if (session.getCharacterId() != null) {
                playerService.persistAndRemove(session.getCharacterId());
            }
        }
        sessionManager.removeSession(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Exception caught from channel: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
