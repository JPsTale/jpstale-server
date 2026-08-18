package org.jpstale.server.game.network;

import io.netty.channel.Channel;
import lombok.Data;
import org.jpstale.server.proto.base.MessageProto;

/**
 * 玩家会话
 * 绑定 Channel 和玩家信息
 */
@Data
public class PlayerSession {

    private final Channel channel;
    private Long accountId;
    private Long characterId;
    private String characterName;
    private boolean loggedIn;
    private boolean playing;

    // 玩家位置（用于怪物刷怪proximity check）
    private int currentMapId;
    private float x;
    private float y;
    private float z;

    public PlayerSession(Channel channel) {
        this.channel = channel;
        this.loggedIn = false;
        this.playing = false;
    }

    /**
     * 发送消息给客户端
     */
    public void send(MessageProto.ServerMessage message) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(message);
        }
    }

    /**
     * 关闭连接
     */
    public void close() {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    /**
     * 获取远程地址
     */
    public String getRemoteAddress() {
        return channel != null ? channel.remoteAddress().toString() : "unknown";
    }
}
