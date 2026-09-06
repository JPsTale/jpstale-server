package org.jpstale.server.game.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket 调试服务器（浏览器调试用）。
 * <p>
 * 与二进制 protobuf 服务器(10007)共存，浏览器通过 JSON over WebSocket 直连本服务器。
 * 复用 PacketRouter 路由到同一套业务 Service（登录/角色/移动/刷怪），保证调试的就是真实游戏逻辑。
 */
@Configuration
public class WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    @Value("${pt.game.ws-port:10008}")
    private int wsPort;

    @Autowired
    private PacketRouterHandler packetRouterHandler;

    @Autowired
    private ServerHeartbeatHandler serverHeartbeatHandler;

    @Bean
    public ApplicationRunner nettyWebSocketServerRunner() {
        return args -> {
            NioEventLoopGroup boss = new NioEventLoopGroup(1);
            NioEventLoopGroup worker = new NioEventLoopGroup();
            try {
                new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                .addLast(new IdleStateHandler(120, 0, 0, TimeUnit.SECONDS))
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(64 * 1024))
                                .addLast(new WebSocketServerProtocolHandler("/ws"))
                                .addLast(serverHeartbeatHandler)
                                .addLast(packetRouterHandler);
                        }
                    })
                    .bind(wsPort)
                    .sync();
                log.info("PT Game Server (WebSocket) listening on port {}", wsPort);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };
    }
}
