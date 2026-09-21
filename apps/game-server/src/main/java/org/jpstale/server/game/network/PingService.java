package org.jpstale.server.game.network;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.proto.base.C2S_Ping;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.S2C_Pong;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Ping 消息处理
 * 处理客户端心跳（技术层）
 */
@Slf4j
@Component
public class PingService {

    /**
     * 服务器权威时钟（毫秒）。
     * <p>
     * 忠实原版 PT：服务端用 GetTickCount()（进程启动至今的单调毫秒）作为世界时间源，
     * 客户端按 GAME_WORLDTIME_MIN=800（800ms = 1 游戏分钟）换算昼夜。
     * 用 {@link System#nanoTime()} 的单调语义模拟 GetTickCount，不受系统改时影响。
     */
    static long nowWorldTimeMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    /**
     * 报文入口：心跳
     */
    @GamePacketHandler(ClientMessage.PING_FIELD_NUMBER)
    public void handlePing(PlayerSession session, ClientMessage message) {
        C2S_Ping ping = message.getPing();

        if (session != null) {
            // 回复 Pong：timestamp 填服务器权威时钟（非回显客户端值）
            S2C_Pong pong = S2C_Pong.newBuilder()
                .setTimestamp(nowWorldTimeMs())
                .build();

            session.send(ServerMessage.newBuilder()
                .setPong(pong)
                .build());

            log.debug("Responded to ping from {}", session.getRemoteAddress());
        }
    }
}