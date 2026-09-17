package org.jpstale.server.game.network;

import io.netty.channel.Channel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.proto.base.S2C_Batch;
import org.jpstale.server.proto.base.ServerMessage;

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
// pending 是**可变**的合批队列：必须排除在 equals/hashCode 之外，否则它的内容一变，
// 该 session 作为 key 的哈希就变（HashMap/HashSet 里会查不到）—— 这类 bug 症状离病根很远。
@ToString(exclude = {"channel", "entity", "pending"})
@EqualsAndHashCode(exclude = {"channel", "entity", "pending"})
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
    /**
     * 移动上报的**统计计数**（收 / 应用 / 被限速拒绝），供诊断日志用：
     * "位置没更新"可能是"没收到"，也可能是"收到了被限速丢了" —— 两种情况症状一样，
     * 只有把三个数字一起打出来才分得清（用户 2026-09-14 要求"服务端加日志了吗"）。
     * `long` 而非 int：这是纯计数，溢出无所谓，但要避免读改写竞争带来的可见性问题（volatile）。
     */
    private volatile long moveRecv;
    private volatile long moveApplied;
    private volatile long moveRejected;
    /** 上次打移动汇总日志的时刻（毫秒）；0 = 还没打过 */
    /** 当前 pending 那条上报的**到达时刻**（服务端时钟，与客户端时钟无关） */
    private volatile long pendingMoveArrivalMs;
    /**
     * 上次**成功应用**的那条上报的**到达时刻**。限速的 `dt` 必须用它来算（而不是"应用发生的时刻"）：
     * 应用的坐标是客户端在几十毫秒前生成的，而"应用时刻"只在 tick 上，两者之间的差会把报告延迟漏掉；
     * 一旦上报被覆盖或丢包（用户 2026-09-14 指出：无论什么频率都会有丢包与时间差），漏掉的还不止一个周期
     * ⇒ 合法的移动会被误判超速。用"两条上报到达时刻之差"时，`dist` 与 `dt` 来自**同一对端点**，
     * 丢一条就两个都变大，天然自洽。
     */
    private volatile long lastAppliedReportArrivalMs;
    public long getPendingMoveArrivalMs() { return pendingMoveArrivalMs; }
    public void setPendingMoveArrivalMs(long t) { pendingMoveArrivalMs = t; }
    public long getLastAppliedReportArrivalMs() { return lastAppliedReportArrivalMs; }
    public void setLastAppliedReportArrivalMs(long t) { lastAppliedReportArrivalMs = t; }

    private volatile long moveDiagAt;

    public void noteMoveReceived() { moveRecv++; }
    public void noteMoveApplied() { moveApplied++; }
    public void noteMoveRejected() { moveRejected++; }
    public long getMoveDiagAt() { return moveDiagAt; }
    public void setMoveDiagAt(long t) { moveDiagAt = t; }
    /** 汇总计数（读后清零）：返回 [收, 应用, 拒绝] */
    public long[] drainMoveCounters() {
        long[] out = { moveRecv, moveApplied, moveRejected };
        moveRecv = 0;
        moveApplied = 0;
        moveRejected = 0;
        return out;
    }
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
     * 本 tick 待发消息（合批队列，见 proto 里 S2C_Batch 的注释）。
     *
     * 用 ConcurrentLinkedQueue：入队可能来自**主循环线程**（AOI/战斗/移动广播），也可能来自
     * **Netty IO 线程**（如玩家击杀怪触发的死亡广播），而 flush 在主循环 —— 必须线程安全。
     */
    private final java.util.Queue<ServerMessage> pending =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * 发送消息给客户端。
     *
     * 默认**只入队**，由 tick 末尾的 {@link #flushPending()} 合批发出：同一 tick 内发给同一个
     * 玩家的多条消息 → 一个 S2C_Batch → **一次编码、一次 writeAndFlush**。
     * 对延迟敏感的少数消息（见 {@link #isImmediate}）直接发。
     */
    public void send(ServerMessage message) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        if (isImmediate(message)) {
            channel.writeAndFlush(message);
            return;
        }
        pending.add(message);
    }

    /**
     * 必须**立即**发送、不吃合批延迟的几类。每一条都有具体理由，不要图省事往里加：
     *  - `pong`：客户端拿它算 RTT / 时间同步，延迟会直接计进往返时间；
     *  - 登录响应 / 角色列表 / 建角结果：请求-响应型，而且发生在"还没进游戏循环"的阶段 ——
     *    那时 tick 未必在跑，入了队可能永远等不到 flush；
     *  - `disconnect`：断开通知，等下一个 tick 没有意义。
     */
    private static boolean isImmediate(ServerMessage m) {
        return m.hasPong() || m.hasLoginResponse() || m.hasCharacterList()
            || m.hasCreateCharacterResult() || m.hasDisconnect();
    }

    /** 把本 tick 累积的消息一次发出（由 {@code GameServer.tick} 每 tick 调一次，20Hz） */
    public void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        if (channel == null || !channel.isActive()) {
            pending.clear();
            return;
        }
        ServerMessage first = pending.poll();
        if (first == null) {
            return;
        }
        // 只有一条时不包信封：S2C_Batch 自身的 tag+length 反而比直接发更大
        if (pending.isEmpty()) {
            channel.writeAndFlush(first);
            return;
        }
        S2C_Batch.Builder batch = S2C_Batch.newBuilder();
        batch.addMessages(first);
        ServerMessage m;
        while ((m = pending.poll()) != null) {
            batch.addMessages(m);
        }
        channel.writeAndFlush(ServerMessage.newBuilder().setBatch(batch).build());
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
