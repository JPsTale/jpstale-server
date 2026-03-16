package org.jpstale.server.core;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.jpstale.server.common.struct.packets.Packet;
import org.jpstale.server.common.struct.packets.PacketNetIdentifier;
import org.jpstale.server.common.struct.packets.PacketNetPlayerWorldToken;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 登录服 / 游戏服 之间 Net* 通道的通用管理器。
 *
 * - 当 role=login 时：
 *   - 读取 pt.inter-server.game.addresses，主动连到所有 game 服；
 *   - 对外提供 broadcastNetPacket，用来广播 PacketNet*。
 * - 当 role=game 时：
 *   - 读取 pt.inter-server.login.address，主动连到 login 服；
 *   - 收到 PacketNet* 时，调用 NetServerImpl 的逻辑，例如 NetPlayerWorldToken。
 */
@Slf4j
@Component
public class InterServerChannelManager implements InitializingBean, DisposableBean {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final List<Channel> channels = new CopyOnWriteArrayList<>();

    private final NetServer netServer;

    /**
     * 当前进程角色：login 或 game。
     */
    @Value("${pt.role:login}")
    private String role;

    /**
     * 登录服 -> 各 GameServer 的地址列表。
     * 仅在 role=login 时使用。
     * 例："127.0.0.1:10017,127.0.0.1:10018"
     */
    @Value("${pt.inter-server.game.addresses:127.0.0.1:10017}")
    private String gameAddresses;

    /**
     * GameServer -> LoginServer 的单一地址。
     * 仅在 role=game 时使用。
     * 例："127.0.0.1:10009"
     */
    @Value("${pt.inter-server.login.address:127.0.0.1:10009}")
    private String loginAddress;

    /**
     * 当前进程自身监听的 TCP 端口，用于 NetIdentifier 中回传给对端。
     * - role=login 时：等于 pt.login.port
     * - role=game 时：等于 pt.game.port
     */
    @Value("${pt.self.port:10009}")
    private int selfPort;

    public InterServerChannelManager(NetServer netServer) {
        this.netServer = netServer;
    }

    @Override
    public void afterPropertiesSet() {
        if ("login".equalsIgnoreCase(role)) {
            initAsLogin();
        } else if ("game".equalsIgnoreCase(role)) {
            initAsGame();
        } else {
            log.warn("InterServerChannelManager disabled, unknown pt.role={}", role);
        }
    }

    // ===================== role = login =====================

    private void initAsLogin() {
        log.info("InterServerChannelManager starting as LOGIN, gameAddresses={}", gameAddresses);
        for (String addr : gameAddresses.split(",")) {
            String trimmed = addr.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":");
            if (parts.length != 2) {
                log.warn("Illegal game address: {}", trimmed);
                continue;
            }
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.warn("Illegal game port in address: {}", trimmed);
                continue;
            }
            connectOne(host, port);
        }
    }

    // ===================== role = game =====================

    private void initAsGame() {
        log.info("InterServerChannelManager starting as GAME, loginAddress={}", loginAddress);
        String[] parts = loginAddress.trim().split(":");
        if (parts.length != 2) {
            log.warn("Illegal loginAddress: {}", loginAddress);
            return;
        }
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.warn("Illegal login port in loginAddress: {}", loginAddress);
            return;
        }
        connectOne(host, port);
    }

    // ===================== 连接建立 & NetIdentifier =====================

    private void connectOne(String host, int port) {
        Bootstrap bs = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                                .addLast("interServerHandler", new SimpleChannelInboundHandler<ByteBuf>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                        handleInterServerPacket(ctx, msg);
                                    }
                                });
                    }
                });

        bs.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel ch = future.channel();
                channels.add(ch);
                log.info("InterServer connected to {}:{} (role={})", host, port, role);
                sendNetIdentifier(ch);
            } else {
                log.warn("InterServer connect to {}:{} failed: {}",
                        host, port, future.cause().getMessage());
            }
        });
    }

    private void sendNetIdentifier(Channel ch) {
        PacketNetIdentifier p = new PacketNetIdentifier();
        p.setPktHeader(PacketHeader.PKTHDR_NetIdentifier);
        // C++: iServerID = SERVER_CODE -> 0 = Login, 1+ = Game，这里简单按角色设 0/1
        p.setServerId("login".equalsIgnoreCase(role) ? 0 : 1);
        p.setPort(selfPort);
        p.setPasswordNum(0x45821547); // 和 C++ 一致

        byte[] data = p.toWireBytes();
        ch.writeAndFlush(ch.alloc().buffer(data.length).writeBytes(data));
    }

    // ===================== 收到 Net* 包时的处理 =====================

    private void handleInterServerPacket(ChannelHandlerContext ctx, ByteBuf msg) {
        int readable = msg.readableBytes();
        if (readable < Packet.SIZE_OF) return;

        byte[] arr = new byte[readable];
        msg.readBytes(arr);
        ByteBuffer buf = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN);

        int header = buf.getInt(4);
        PacketHeader pktHeader = PacketHeader.fromValue(header);

        if (pktHeader == null) {
            log.warn("Unknown Net* header=0x{} from {}",
                    Integer.toHexString(header), ctx.channel().remoteAddress());
            return;
        }

        switch (pktHeader) {
            case PKTHDR_NetPlayerWorldToken -> {
                PacketNetPlayerWorldToken p = new PacketNetPlayerWorldToken();
                p.readFrom(buf);
                // 不区分 login/game：统一交给 NetServerImpl 的 Map 逻辑
                netServer.addWorldConnectAllowance(p.getToken(), p.getTokenPass());
                log.info("InterServer got NetPlayerWorldToken token={} from {}",
                        p.getToken(), ctx.channel().remoteAddress());
            }
            // 其他 Net* 包后续可以按需补充 case
            default -> {
                // 先忽略未实现的 Net* 包，避免打断通道
                log.trace("InterServer received Net* {} (ignored for now) from {}",
                        pktHeader, ctx.channel().remoteAddress());
            }
        }
    }

    // ===================== 对外发送 Net* 包 =====================

    /**
     * 登录服调用：把一个 Net* 包广播到所有已连接的 GameServer。
     */
    public void broadcastNetPacket(Packet packet) {
        if (!"login".equalsIgnoreCase(role)) {
            log.warn("broadcastNetPacket called but role={} != login", role);
            return;
        }
        byte[] data = packet.toWireBytes();
        for (Channel ch : channels) {
            if (ch.isActive()) {
                ch.writeAndFlush(ch.alloc().buffer(data.length).writeBytes(data));
            }
        }
    }

    @Override
    public void destroy() {
        for (Channel ch : channels) {
            if (ch.isOpen()) {
                ch.close();
            }
        }
        group.shutdownGracefully();
    }
}