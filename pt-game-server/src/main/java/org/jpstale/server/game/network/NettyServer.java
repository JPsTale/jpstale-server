package org.jpstale.server.game.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.jpstale.server.common.network.codec.ProtobufDecoder;
import org.jpstale.server.common.network.codec.ProtobufEncoder;
import org.jpstale.server.game.network.PacketRouterHandler;
import org.jpstale.server.game.network.ServerHeartbeatHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class NettyServer {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    @Value("${pt.game.port:10007}")
    private int port;

    @Autowired
    private PacketRouterHandler packetRouterHandler;

    @Autowired
    private ServerHeartbeatHandler serverHeartbeatHandler;

    @Bean
    public ApplicationRunner nettyGameServerRunner() {
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
                                .addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS))
                                .addLast(serverHeartbeatHandler)
                                .addLast(new LengthFieldBasedFrameDecoder(16 * 1024 * 1024, 0, 4, 0, 4))
                                .addLast(new ProtobufDecoder())
                                .addLast(new ProtobufEncoder())
                                .addLast(packetRouterHandler);
                        }
                    })
                    .bind(port)
                    .sync();
                log.info("PT Game Server (Netty) listening on port {}", port);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };
    }
}
