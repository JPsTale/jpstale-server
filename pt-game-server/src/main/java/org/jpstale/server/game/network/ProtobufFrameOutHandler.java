package org.jpstale.server.game.network;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jpstale.server.proto.base.MessageProto;

public class ProtobufFrameOutHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof MessageProto.ServerMessage serverMsg) {
            byte[] bytes = serverMsg.toByteArray();
            ctx.write(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)), promise);
        } else {
            ctx.write(msg, promise);
        }
    }
}
