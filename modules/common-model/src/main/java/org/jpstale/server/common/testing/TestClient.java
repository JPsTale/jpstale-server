package org.jpstale.server.common.testing;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.jpstale.server.common.struct.packets.*;
import org.jpstale.server.common.struct.account.PacketAccountLoginCode;
import org.jpstale.server.common.codec.PacketSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * 简单的测试客户端
 * 用于验证Login Server的协议实现
 */
public class TestClient {
    private static final Logger log = LoggerFactory.getLogger(TestClient.class);

    private final String host;
    private final int port;

    public TestClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void run() throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioSocketChannel.class)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast(new TestClientHandler());
                 }
             });

            Channel ch = b.connect(host, port).sync().channel();

            // 发送登录请求
            System.out.println("Sending login request...");
            PacketLoginUser loginPacket = createLoginPacket();
            byte[] data = loginPacket.toWireBytes();
            ch.writeAndFlush(ch.alloc().buffer(data.length).writeBytes(data));

            // 等待响应或关闭
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private PacketLoginUser createLoginPacket() {
        PacketLoginUser packet = new PacketLoginUser();
        packet.setUnk(new int[]{0, 0, 0});
        packet.setUserId("testuser");
        packet.setPassword("5E884898DA28047151"); // SHA256 UPPER("TESTUSER:password")
        packet.setMacAddr("001122334455");
        packet.setPcname("TestPC");
        packet.setSerialHd(123456789);
        packet.setVideoName("TestVideoCard");
        packet.setHardwareId("HARDWARE123");
        packet.setWidthScreen(1920);
        packet.setHeightScreen(1080);
        packet.setSystemOs(10);
        packet.setVersion(1);
        packet.setPktHeader(org.jpstale.server.common.enums.packets.PacketHeader.PKTHDR_LoginUser);
        return packet;
    }

    @ChannelHandler.Sharable
    private static class TestClientHandler extends SimpleChannelInboundHandler<io.netty.buffer.ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf msg) throws Exception {
            int readable = msg.readableBytes();
            if (readable < 8) {
                log.error("Packet too short: {} bytes", readable);
                return;
            }

            byte[] arr = new byte[readable];
            msg.readBytes(arr);

            // 分析包头
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(arr).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            short length = buf.getShort();
            byte encKeyIndex = buf.get();
            byte encrypted = buf.get();
            int header = buf.getInt();

            org.jpstale.server.common.enums.packets.PacketHeader packetHeader =
                org.jpstale.server.common.enums.packets.PacketHeader.fromValue(header);

            log.info("Received packet: {} (0x{}), size: {}, from: {}",
                    packetHeader,
                    Integer.toHexString(header),
                    length,
                    ctx.channel().remoteAddress());

            // 如果是AccountLoginCode，显示详细信息
            if (packetHeader == org.jpstale.server.common.enums.packets.PacketHeader.PKTHDR_AccountLoginCode) {
                buf.rewind();
                PacketAccountLoginCode codePacket = new PacketAccountLoginCode();
                codePacket.readFrom(buf);

                log.info("Login result: {}, message: {}",
                        codePacket.getCode(),
                        codePacket.getMessage());
            }
            // 如果是UserInfo，显示角色数量
            else if (packetHeader == org.jpstale.server.common.enums.packets.PacketHeader.PKTHDR_UserInfo) {
                buf.rewind();
                PacketUserInfo userInfo = new PacketUserInfo();
                userInfo.readFrom(buf);

                log.info("User: {}, Character count: {}",
                        userInfo.getUserId(),
                        userInfo.getCharCount());
            }
            // 如果是ServerList，显示服务器信息
            else if (packetHeader == org.jpstale.server.common.enums.packets.PacketHeader.PKTHDR_ServerList) {
                buf.rewind();
                PacketServerList serverList = new PacketServerList();
                serverList.readFrom(buf);

                log.info("Received server list with {} game servers",
                        serverList.getHeader().getGameServers());

                for (int i = 0; i < serverList.getHeader().getGameServers(); i++) {
                    org.jpstale.server.common.struct.Server server = serverList.getServers()[i];
                    log.info("  Server {}: {} - {}:{}",
                            i,
                            server.getName(),
                            server.getIp()[0],
                            server.getPort()[0]);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Exception: ", cause);
            ctx.close();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            log.info("Connected to server: {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.info("Disconnected from server");
        }
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 10009; // Login Server port

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        System.out.println("Connecting to " + host + ":" + port);

        TestClient client = new TestClient(host, port);
        try {
            client.run();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}