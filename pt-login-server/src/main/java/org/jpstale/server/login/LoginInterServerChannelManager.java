package org.jpstale.server.login;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.AbstractInterServerChannelManager;
import org.jpstale.server.core.InterServerBroadcastService;
import org.jpstale.server.core.NetServer;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.jpstale.server.common.struct.packets.Packet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Login Server 的服务器间通道管理器。
 * 负责监听来自 Game Server 的连接并处理通信。
 */
@Slf4j
@Component
public class LoginInterServerChannelManager extends AbstractInterServerChannelManager implements InterServerBroadcastService {

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final List<Channel> gameServerChannels = new CopyOnWriteArrayList<>();

    @Value("${pt.login.port:10009}")
    private int loginPort;

    private Channel serverChannel;

    public LoginInterServerChannelManager(NetServer netServer) {
        super(netServer);
    }

    @PostConstruct
    public void init() {
        log.info("LoginInterServerChannelManager starting, listening on port {}", loginPort);

        ServerBootstrap bs = new ServerBootstrap();
        bs.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("interServerHandler", createChannelHandler());
                    }
                });

        try {
            ChannelFuture future = bs.bind(loginPort).sync();
            serverChannel = future.channel();
            log.info("LoginServer InterServer listener started on port {}", loginPort);

            // 添加关闭钩子
            serverChannel.closeFuture().addListener((ChannelFutureListener) f -> {
                log.info("LoginServer InterServer listener closed");
            });
        } catch (InterruptedException e) {
            log.error("Failed to start LoginServer InterServer listener", e);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String getRole() {
        return "login";
    }

    @Override
    public int getSelfPort() {
        return loginPort;
    }

    @Override
    protected void handlePacketByRole(ChannelHandlerContext ctx, ByteBuffer buf, PacketHeader pktHeader) {
        switch (pktHeader) {
            case PKTHDR_NetIdentifier -> {
                log.info("Received NetIdentifier from GameServer: {}", ctx.channel().remoteAddress());
                // 可以在这里验证 GameServer 的身份
                gameServerChannels.add(ctx.channel());

                // 添加连接关闭监听
                ctx.channel().closeFuture().addListener((ChannelFutureListener) f -> {
                    gameServerChannels.remove(ctx.channel());
                    log.info("GameServer disconnected: {}", ctx.channel().remoteAddress());
                });
            }
            case PKTHDR_NetPlayerWorldToken -> {
                // LoginServer 通常不接收这个包，但以防万一
                log.warn("Unexpected NetPlayerWorldToken received from GameServer");
            }
            default -> {
                // 其他 Net* 包的处理
                log.debug("LoginServer received Net* {} from {}", pktHeader, ctx.channel().remoteAddress());
            }
        }
    }

    /**
     * 实现 InterServerBroadcastService 接口
     */
    @Override
    public void broadcastPacket(Packet packet) {
        broadcastPacketToGameServers(packet);
    }

    /**
     * 向所有已连接的 Game Server 广播数据包。
     */
    public void broadcastPacketToGameServers(Packet packet) {
        byte[] data = packet.toWireBytes();

        // 使用迭代器避免并发修改异常
        for (Channel ch : gameServerChannels) {
            if (ch.isActive()) {
                ch.writeAndFlush(ch.alloc().buffer(data.length).writeBytes(data));
            }
        }

        log.debug("Broadcasted packet {} to {} GameServers",
                packet.getClass().getSimpleName(), gameServerChannels.size());
    }

    /**
     * 获取当前连接的 Game Server 数量。
     */
    public int getConnectedGameServerCount() {
        return (int) gameServerChannels.stream().filter(Channel::isActive).count();
    }

    /**
     * 获取所有已连接的 Game Server 地址。
     */
    public java.util.List<String> getConnectedGameServerAddresses() {
        return gameServerChannels.stream()
                .filter(Channel::isActive)
                .map(ch -> ch.remoteAddress().toString())
                .toList();
    }

    @Override
    protected void destroyInternal() {
        if (serverChannel != null) {
            serverChannel.close();
        }

        for (Channel ch : gameServerChannels) {
            if (ch.isOpen()) {
                ch.close();
            }
        }

        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}