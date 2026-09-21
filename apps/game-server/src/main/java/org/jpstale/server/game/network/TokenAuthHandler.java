package org.jpstale.server.game.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import org.jpstale.server.game.service.GameTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Sharable
public class TokenAuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthHandler.class);
    public static final AttributeKey<Long> ACCOUNT_ID = AttributeKey.valueOf("accountId");

    private final GameTokenService tokenService;

    public TokenAuthHandler(GameTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {
            String uri = request.uri();
            String token = extractToken(uri);

            if (token == null) {
                log.warn("No token in request: {}", uri);
                reject(ctx, "Missing token");
                return;
            }

            Long accountId = tokenService.validate(token);
            if (accountId == null) {
                log.warn("Invalid token: {}", token);
                reject(ctx, "Invalid token");
                return;
            }

            log.info("Token auth OK: accountId={}, remote={}", accountId, ctx.channel().remoteAddress());
            ctx.channel().attr(ACCOUNT_ID).set(accountId);
            // Strip query string so WebSocketServerProtocolHandshakeHandler matches "/ws"
            request.setUri(request.uri().split("\\?")[0]);
        }

        ctx.fireChannelRead(msg);
    }

    private String extractToken(String uri) {
        int q = uri.indexOf('?');
        if (q < 0) return null;
        String query = uri.substring(q + 1);
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "token".equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private void reject(ChannelHandlerContext ctx, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
        response.headers().set("Content-Type", "text/plain");
        response.content().writeBytes(message.getBytes());
        ctx.writeAndFlush(response).addListener(f -> ctx.close());
    }
}
