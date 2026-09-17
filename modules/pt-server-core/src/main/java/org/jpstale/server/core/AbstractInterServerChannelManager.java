package org.jpstale.server.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.jpstale.server.common.struct.packets.Packet;
import org.jpstale.server.common.struct.packets.PacketNetIdentifier;
import org.jpstale.server.common.struct.packets.PacketNetPlayerWorldToken;
import org.springframework.beans.factory.DisposableBean;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 服务器间通道管理器的抽象基类。
 * 提供公共的包处理逻辑，具体的连接逻辑由子类实现。
 */
@Slf4j
public abstract class AbstractInterServerChannelManager implements DisposableBean {

    protected final NetServer netServer;

    public AbstractInterServerChannelManager(NetServer netServer) {
        this.netServer = netServer;
    }

    /**
     * 初始化连接，由子类实现具体逻辑。
     */
    public abstract void init();

    /**
     * 获取当前服务器的角色。
     */
    public abstract String getRole();

    /**
     * 获取当前服务器的端口。
     */
    public abstract int getSelfPort();

    /**
     * 创建通道处理器。
     */
    protected SimpleChannelInboundHandler<ByteBuf> createChannelHandler() {
        return new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                handleInterServerPacket(ctx, msg);
            }
        };
    }

    /**
     * 发送 NetIdentifier 包。
     */
    protected void sendNetIdentifier(io.netty.channel.Channel ch, String role, int selfPort) {
        PacketNetIdentifier p = new PacketNetIdentifier();
        p.setPktHeader(PacketHeader.PKTHDR_NetIdentifier);
        // C++: iServerID = SERVER_CODE -> 0 = Login, 1+ = Game，这里简单按角色设 0/1
        p.setServerId("login".equalsIgnoreCase(role) ? 0 : 1);
        p.setPort(selfPort);
        p.setPasswordNum(0x45821547); // 和 C++ 一致

        byte[] data = p.toWireBytes();
        ch.writeAndFlush(ch.alloc().buffer(data.length).writeBytes(data));
    }

    /**
     * 处理服务器间数据包（公共逻辑）。
     */
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

        handlePacketByRole(ctx, buf, pktHeader);
    }

    /**
     * 根据角色处理不同的数据包，由子类实现。
     */
    protected abstract void handlePacketByRole(ChannelHandlerContext ctx, ByteBuffer buf, PacketHeader pktHeader);

    /**
     * 处理 NetPlayerWorldToken 包（公共逻辑）。
     */
    protected void handleNetPlayerWorldToken(ChannelHandlerContext ctx, ByteBuffer buf) {
        PacketNetPlayerWorldToken p = new PacketNetPlayerWorldToken();
        p.readFrom(buf);
        // 统一交给 NetServerImpl 的 Map 逻辑
        netServer.addWorldConnectAllowance(p.getToken(), p.getTokenPass());
        log.info("InterServer got NetPlayerWorldToken token={} from {}",
                p.getToken(), ctx.channel().remoteAddress());
    }

    @Override
    public void destroy() {
        destroyInternal();
    }

    /**
     * 销毁资源，由子类实现。
     */
    protected abstract void destroyInternal();
}