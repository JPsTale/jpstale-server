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
    private SessionState state = SessionState.CONNECTED;

    /** 断线后是否允许重连（顶号踢人时置 false） */
    private boolean allowReconnect = true;

    /** 断线时是否已下发过重连 token（避免 READER_IDLE 与 channelInactive 双路径重复生成） */
    private boolean reconnectTokenIssued = false;

    // 玩家位置（world double：(rawX/256, rawY/256, -rawZ/256)，北正，与碰撞同域）
    private int currentMapId;
    private double x;
    private double y;
    private double z;

    // ======== 服务端权威移动状态 ========
    /** 移动方向（弧度，0=Z+方向，atan2(dx,dz)） */
    private double moveAngle;
    /** 移动状态机（IDLE/WALK/RUN/ATTACK/DEAD） */
    private PlayerMoveState moveState = PlayerMoveState.IDLE;

    // 玩家战斗状态（调试工具用）
    private int hp = 100;
    private int maxHp = 100;
    private int mp = 50;
    private int maxMp = 50;
    private int level = 1;

    public PlayerSession(Channel channel) {
        this.channel = channel;
    }

    /** 是否已登录（状态机：非 CONNECTED） */
    public boolean isLoggedIn() {
        return state.isLoggedIn();
    }

    /** 是否已在游戏中（状态机：PLAYING） */
    public boolean isPlaying() {
        return state.isPlaying();
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
     * 发送原始文本（WebSocket JSON 调试通道用）
     */
    public void sendText(String text) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(text));
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
