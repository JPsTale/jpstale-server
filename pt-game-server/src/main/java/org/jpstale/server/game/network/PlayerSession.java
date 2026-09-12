package org.jpstale.server.game.network;

import io.netty.channel.Channel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.proto.base.MessageProto;

/**
 * 玩家会话(纯网络/会话层,D11/M3)。
 *
 * 只承担:连接、身份(charId/account/name/state)、指向场上玩家实体的引用(entity)、
 * 以及"客户端上报移动的 IO 缓冲"(pendingMove*,属网络输入缓冲,核心 loop 消费后写实体)。
 *
 * 不持有任何游戏状态:坐标/移动/血量/等级一律在 PlayerEntity
 * (坐标/移动状态在实体字段,属性/血量/等级经实体转发到其 Player)。
 * 业务代码应直接操作 session.getEntity(),不要再经 session 转发游戏字段。
 */
@Getter
@Setter
@ToString(exclude = {"channel", "entity"})
@EqualsAndHashCode(exclude = {"channel", "entity"})
public class PlayerSession {

    private final Channel channel;
    private Long accountId;
    private Long characterId;
    private String characterName;
    private SessionState state = SessionState.CONNECTED;

    /** 账号登录 token（token auth 时写入；大退 logout 时用于失效 Redis key）。大小退共用同一 session。 */
    private String token;

    /** 断线后是否允许重连（顶号踢人时置 false） */
    private boolean allowReconnect = true;

    /** 断线时是否已下发过重连 token（避免 READER_IDLE 与 channelInactive 双路径重复生成） */
    private boolean reconnectTokenIssued = false;

    /** 场上玩家实体(游戏数据/坐标权威所在) */
    private PlayerEntity entity;

    // ======== 客户端位置上权威（方向二）：Netty IO 线程写待处理移动，核心 loop 消费 ========
    private volatile int pendingMoveMode = -1;
    private volatile double pendingMoveX;
    private volatile double pendingMoveY;
    private volatile double pendingMoveZ;
    private volatile double pendingMoveAngle;
    /** 动画覆盖：0=按 mode 推导；非 0（如掉落 0x70/0x71/0x72）时 S2C 广播用它 */
    private volatile int pendingMoveAnimState;
    /**
     * 该玩家**自己正播的那一条动画**：.inx 条目索引 + 语义 ID（如 stand_unarmed.m4.10）。
     *
     * 服务端只做**透传**，不理解其含义 —— 「动画状态」只说明播哪一类（站/走/跑），
     * 同一类下还有多个变体，而变体原先由各客户端各自随机选 → 同一个角色在别人屏幕上
     * 播的是另一条。由该玩家上报、经 S2C_PlayerMove 原样广播后，旁观者直接播同一条。
     */
    private volatile int pendingMoveAnimIndex;
    private volatile String pendingMoveAnimClip = "";
    /** 上一条已接受位置的时间戳(ms)；0=尚未接受（首条不限速） */
    private long lastMoveAcceptedMs;

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
