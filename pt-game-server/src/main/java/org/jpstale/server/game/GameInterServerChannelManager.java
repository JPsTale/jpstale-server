package org.jpstale.server.game;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.AbstractInterServerChannelManager;
import org.jpstale.server.core.NetServer;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Game Server 的服务器间通道管理器。
 * 负责与 Login Server 建立连接并处理通信。
 */
@Slf4j
@Component
public class GameInterServerChannelManager extends AbstractInterServerChannelManager {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final AtomicReference<Channel> loginServerChannel = new AtomicReference<>();

    @Value("${pt.inter-server.login.address:127.0.0.1:10009}")
    private String loginAddress;

    @Value("${pt.game.port:10007}")
    private int gamePort;

    public GameInterServerChannelManager(NetServer netServer) {
        super(netServer);
    }

    @PostConstruct
    public void init() {
        log.info("GameInterServerChannelManager starting, loginAddress={}", loginAddress);

        String[] parts = loginAddress.trim().split(":");
        if (parts.length != 2) {
            log.error("Illegal loginAddress: {}", loginAddress);
            return;
        }

        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.error("Illegal login port in loginAddress: {}", loginAddress);
            return;
        }

        connectToLoginServer(host, port);
    }

    private void connectToLoginServer(String host, int port) {
        Bootstrap bs = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast("interServerHandler", createChannelHandler());
                    }
                });

        bs.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel ch = future.channel();
                loginServerChannel.set(ch);
                log.info("GameServer connected to LoginServer at {}:{}", host, port);

                // 发送 NetIdentifier 包
                sendNetIdentifier(ch, getRole(), getSelfPort());

                // 添加连接关闭监听
                ch.closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                    if (!closeFuture.isSuccess()) {
                        log.warn("Connection to LoginServer closed");
                    }
                    loginServerChannel.set(null);
                    // 可以添加重连逻辑
                });
            } else {
                log.error("Failed to connect to LoginServer at {}:{}: {}",
                        host, port, future.cause().getMessage());
                // 可以添加重连逻辑
            }
        });
    }

    @Override
    public String getRole() {
        return "game";
    }

    @Override
    public int getSelfPort() {
        return gamePort;
    }

    @Override
    protected void handlePacketByRole(ChannelHandlerContext ctx, ByteBuffer buf, PacketHeader pktHeader) {
        switch (pktHeader) {
            case PKTHDR_NetPlayerWorldToken -> {
                handleNetPlayerWorldToken(ctx, buf);
            }
            case PKTHDR_NetIdentifier -> {
                log.info("Received NetIdentifier from LoginServer");
                // 可以在这里处理 LoginServer 的响应
            }
            default -> {
                // 其他 Net* 包的处理
                log.debug("GameServer received Net* {} from {}", pktHeader, ctx.channel().remoteAddress());
            }
        }
    }

    /**
     * 向 Login Server 发送数据包。
     */
    public boolean sendPacketToLoginServer(byte[] data) {
        Channel ch = loginServerChannel.get();
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(ch.alloc().buffer(data.length).writeBytes(data));
            return true;
        }
        log.warn("No active connection to LoginServer");
        return false;
    }

    @Override
    protected void destroyInternal() {
        Channel ch = loginServerChannel.get();
        if (ch != null && ch.isOpen()) {
            ch.close();
        }
        group.shutdownGracefully();
    }
}