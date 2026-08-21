package org.jpstale.server.game.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandler.Sharable;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.common.ValidationInterceptor;
import org.jpstale.server.game.common.ValidationResult;
import org.jpstale.server.game.service.AOIManager;
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

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageProto.ClientMessage msg) throws Exception {
        PlayerSession session = sessionManager.getSession(ctx.channel());

        // 输入验证
        if (session != null && session.isPlaying()) {
            ValidationResult result = validationInterceptor.validate(session, msg);
            if (!result.isValid()) {
                // 验证失败，发送错误消息
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

        // 路由消息到对应的处理器
        packetRouter.route(session, msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("Client connected: {}", ctx.channel().remoteAddress());
        // 创建新的 Session
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
