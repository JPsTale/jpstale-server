package org.jpstale.server.game.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jpstale.server.proto.base.MessageProto;

public class ProtobufFrameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
        io.netty.buffer.ByteBuf buf = frame.content();
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        MessageProto.ClientMessage msg = MessageProto.ClientMessage.parseFrom(bytes);
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[WS-Proto] error: " + cause.getMessage());
        ctx.close();
    }
}
