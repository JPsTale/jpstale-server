package org.jpstale.server.game.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.common.ValidationInterceptor;
import org.jpstale.server.game.common.ValidationResult;
import org.jpstale.server.game.entity.PlayerEntity;
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
                sendLogoutAndClose(ctx, "非法连接协议");
                return;
            }
            String token = json.has("token") ? json.get("token").asText() : null;
            if (token == null || token.isBlank()) {
                log.warn("Missing token in auth.token message");
                sendLogoutAndClose(ctx, "缺少登录凭证");
                return;
            }
            Long accountId = gameTokenService.validate(token);
            if (accountId == null) {
                log.warn("Invalid token: {}", token);
                // token 已失效（大退后 / 被顶号）：通知客户端登出，避免客户端本地状态漂移
                sendLogoutAndClose(ctx, "登录已失效，请重新登录");
                return;
            }

            // 顶号踢人：同一账号已有在线 session（新连接 token 合法）→ 存档+失效 token+下发登出+关旧连接
            PlayerSession old = sessionManager.getSessionByAccountId(accountId);
            if (old != null && old.getChannel() != ctx.channel()) {
                log.info("Account {} logged in from new connection, kicking old session", accountId);
                accountService.kick(old, "你的账号在别处登录");
            }

            PlayerSession session = sessionManager.getSession(ctx.channel());
            if (session == null) {
                session = sessionManager.createSession(ctx.channel());
            }
            session.setAccountId(accountId);
            session.setToken(token);
            session.setState(SessionState.SERVER_SELECTED);
            sessionManager.bindAccountId(ctx.channel(), accountId);
            log.info("Token auth OK: accountId={}, remote={}", accountId, ctx.channel().remoteAddress());
            accountService.sendCharacterList(session);
        } catch (Exception e) {
            log.error("Token auth failed", e);
            sendLogoutAndClose(ctx, "登录校验异常");
        }
    }

    /** 下发登出通知再关闭连接（服务端权威登出：让客户端收到 auth.logout 后被动清 token 回登录） */
    private void sendLogoutAndClose(ChannelHandlerContext ctx, String reason) {
        try {
            ctx.writeAndFlush(new TextWebSocketFrame(
                "{\"type\":\"auth.logout\",\"data\":{\"success\":false,\"reason\":\"" + reason + "\"}}"))
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.warn("Send logout notice failed (closing anyway): {}", e.getMessage());
            ctx.close();
        }
    }

    /** Channel 版本（顶号踢旧连接用） */
    private void sendLogoutAndClose(Channel ch, String reason) {
        sendLogoutAndClose(ch.pipeline().context(this), reason);
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
            // ⚠ 先让会话**不再"在游戏中"**：怪物 AI 的 AiContext 持有 PlayerEntity，
            //   而 AiEngine.validateTarget 只按 `entity.isPlaying()` 判断目标是否有效。
            //   断线不清状态 → 幽灵实体被判为**永久有效**目标：怪物隔着半张地图打"上一次会话"
            //   留下的旧实体，旧实体持续掉血→死亡→复活(半血)，而客户端按 playerId 归因到本人，
            //   于是同一角色出现两条 HP 序列、血条狂闪、偶尔跳血（用户 2026-09-12 实测）。
            session.setState(SessionState.CONNECTED);
            // 广播 Disappear 给视野内玩家（登出/断线离开世界）
            PlayerEntity e = session.getEntity();
            if (e != null) {
                aoiManager.onPlayerLeave(e);
                aoiManager.removePlayer(e);
            }
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
