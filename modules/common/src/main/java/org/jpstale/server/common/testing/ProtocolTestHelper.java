package org.jpstale.server.common.testing;

import org.jpstale.server.common.struct.packets.*;
import org.jpstale.server.common.struct.account.PacketAccountLoginCode;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.jpstale.server.common.codec.PacketSender;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/**
 * 协议测试辅助工具
 * 用于生成和验证网络包
 */
public class ProtocolTestHelper {

    /**
     * 创建一个测试用的LoginUser包
     */
    public static PacketLoginUser createTestLoginUser() {
        PacketLoginUser packet = new PacketLoginUser();
        packet.setUnk(new int[]{0, 0, 0});
        packet.setUserId("testuser");
        packet.setPassword("5E884898DA28047151"); // SHA256 of "TESTUSER:password"
        packet.setMacAddr("00:11:22:33:44:55");
        packet.setPcname("TestPC");
        packet.setSerialHd(123456789);
        packet.setVideoName("TestVideo");
        packet.setHardwareId("TEST-HARDWARE-ID");
        packet.setWidthScreen(1920);
        packet.setHeightScreen(1080);
        packet.setSystemOs(10);
        packet.setVersion(1);

        packet.setPktHeader(PacketHeader.PKTHDR_LoginUser);
        return packet;
    }

    /**
     * 打印包的十六进制内容
     */
    public static void printPacketHex(Packet packet) {
        byte[] data = packet.toWireBytes();
        HexFormat format = HexFormat.of();

        System.out.println("Packet " + packet.getPktHeader() + " (" + data.length + " bytes):");
        System.out.println(format.formatHex(data));

        // 打印前64字节的详细分析
        System.out.println("\nHeader analysis:");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        short length = buf.getShort();
        byte encKeyIndex = buf.get();
        byte encrypted = buf.get();
        int header = buf.getInt();

        System.out.printf("  Length: %d (0x%04X)\n", length, length & 0xFFFF);
        System.out.printf("  EncKeyIndex: %d\n", encKeyIndex);
        System.out.printf("  Encrypted: %d\n", encrypted);
        System.out.printf("  Header: 0x%08X (%s)\n", header, packet.getPktHeader().toString());
    }

    /**
     * 测试包的往返序列化
     */
    public static boolean testPacketRoundTrip(Packet packet) {
        try {
            byte[] data = packet.toWireBytes();
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

            // 读取包头
            buf.position(4);
            int headerValue = buf.getInt();
            PacketHeader header = PacketHeader.fromValue(headerValue);

            if (header != packet.getPktHeader()) {
                System.err.println("Header mismatch. Expected: " + packet.getPktHeader() + ", Got: " + header);
                return false;
            }

            // 重新构造包
            buf.rewind();
            Packet reconstructed = packet.getClass().getDeclaredConstructor().newInstance();
            reconstructed.readFrom(buf);

            // 对比关键数据
            if (packet instanceof PacketLoginUser && reconstructed instanceof PacketLoginUser) {
                PacketLoginUser orig = (PacketLoginUser) packet;
                PacketLoginUser recon = (PacketLoginUser) reconstructed;
                return orig.getUserId().equals(recon.getUserId()) &&
                       orig.getPassword().equals(recon.getPassword());
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Protocol Test Helper ===\n");

        // 测试LoginUser包
        System.out.println("\n1. Testing PacketLoginUser:");
        PacketLoginUser loginPacket = createTestLoginUser();
        printPacketHex(loginPacket);

        boolean success = testPacketRoundTrip(loginPacket);
        System.out.println("\nRound-trip test: " + (success ? "SUCCESS" : "FAILED"));

        // 测试AccountLoginCode包
        System.out.println("\n2. Testing PacketAccountLoginCode:");
        PacketAccountLoginCode codePacket = new PacketAccountLoginCode();
        codePacket.setReserved(0);
        codePacket.setCode(org.jpstale.server.common.enums.account.AccountLogin.SUCCESS);
        codePacket.setFailCode(0);
        codePacket.setMessage("Login successful");
        codePacket.setPktHeader(PacketHeader.PKTHDR_AccountLoginCode);

        printPacketHex(codePacket);
        success = testPacketRoundTrip(codePacket);
        System.out.println("\nRound-trip test: " + (success ? "SUCCESS" : "FAILED"));
    }
}