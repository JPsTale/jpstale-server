package org.jpstale.server.common.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;

/**
 * Protobuf 编码器
 * 协议格式：4字节长度 + Protobuf字节
 */
@Slf4j
public class ProtobufEncoder extends MessageToByteEncoder<MessageProto.ServerMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MessageProto.ServerMessage msg, ByteBuf out) throws Exception {
        try {
            // 序列化为字节
            byte[] bytes = msg.toByteArray();

            // 写入4字节长度
            out.writeInt(bytes.length);

            // 写入Protobuf字节
            out.writeBytes(bytes);
        } catch (Exception e) {
            log.error("Failed to encode ServerMessage", e);
        }
    }
}
