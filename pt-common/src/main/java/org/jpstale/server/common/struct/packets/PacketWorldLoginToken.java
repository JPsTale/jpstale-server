package org.jpstale.server.common.struct.packets;

import lombok.Data;
import org.jpstale.server.common.enums.packets.PacketHeader;

import java.nio.ByteBuffer;

/**
 * 对应 packets.h 中 struct PacketWorldLoginToken : Packet。
 * Game Server成功验证后发送给客户端的令牌
 */
@Data
public class PacketWorldLoginToken extends Packet {

    /** 本包体字节数（不含包头）. */
    public static final int SIZE_OF = 65;

    private String tokenPass;  // char TokenPass[65]

    @Override
    public int sizeOf() {
        return super.sizeOf() + SIZE_OF;
    }

    @Override
    protected void readBody(ByteBuffer in) {
        tokenPass = readCString(in, 65);
    }

    @Override
    protected void writeBody(ByteBuffer out) {
        writeCString(out, tokenPass, 65);
    }
}