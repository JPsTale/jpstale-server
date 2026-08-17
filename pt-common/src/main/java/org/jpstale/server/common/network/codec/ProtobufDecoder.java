package org.jpstale.server.common.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.MessageProto;

import java.util.List;

/**
 * Protobuf 解码器
 * 协议格式：4字节长度 + Protobuf字节
 */
@Slf4j
public class ProtobufDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        // 检查可读字节数
        if (msg.readableBytes() < 4) {
            return;
        }

        // 标记读位置
        msg.markReaderIndex();

        // 读取4字节长度
        int length = msg.readInt();

        // 检查长度是否合法
        if (length < 0 || length > msg.readableBytes()) {
            msg.resetReaderIndex();
            log.warn("Invalid message length: {}, readable bytes: {}", length, msg.readableBytes());
            return;
        }

        // 读取Protobuf字节
        byte[] bytes = new byte[length];
        msg.readBytes(bytes);

        try {
            // 解析为ClientMessage
            MessageProto.ClientMessage message = MessageProto.ClientMessage.parseFrom(bytes);
            out.add(message);
        } catch (Exception e) {
            log.error("Failed to parse ClientMessage", e);
        }
    }
}
