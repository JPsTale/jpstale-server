package org.jpstale.server.game.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 服务端心跳处理器
 * 检测空闲连接并断开
 */
@Slf4j
@Component
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ReconnectionManager reconnectionManager;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                // 读空闲超时，断开连接
                log.info("Reader idle timeout, closing connection: {}", ctx.channel().remoteAddress());
                
                PlayerSession session = sessionManager.getSession(ctx.channel());
                if (session != null && session.isPlaying()) {
                    // 生成重连 Token
                    String token = reconnectionManager.generateReconnectToken(session);
                    log.info("Generated reconnect token for player: {}", session.getCharacterName());
                }
                
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
