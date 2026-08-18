package org.jpstale.server.common.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;

import java.util.List;

/**
 * Protobuf 解码器
 * 由 LengthFieldBasedFrameDecoder 提供完整的、已剥离长度字段的帧，
 * 这里直接反序列化为 ClientMessage。
 */
@Slf4j
public class ProtobufDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        if (msg.readableBytes() < 1) {
            return;
        }

        byte[] bytes = new byte[msg.readableBytes()];
        msg.readBytes(bytes);

        try {
            MessageProto.ClientMessage message = MessageProto.ClientMessage.parseFrom(bytes);
            out.add(message);
        } catch (Exception e) {
            log.error("Failed to parse ClientMessage", e);
        }
    }
}
