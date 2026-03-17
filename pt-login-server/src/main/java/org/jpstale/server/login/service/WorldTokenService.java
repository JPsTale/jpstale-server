package org.jpstale.server.login.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.jpstale.server.common.struct.packets.PacketNetPlayerWorldToken;
import org.jpstale.server.core.InterServerBroadcastService;
import org.jpstale.server.core.NetServer;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 登录服世界登录 Token 生成 & 广播服务。
 *
 * 使用方式：
 *   WorldToken token = worldTokenService.generateAndBroadcastToken();
 *   然后把 token.getToken(), token.getTokenPass() 写进发给客户端的“世界登录包”里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldTokenService {

    private final NetServer netServer;
    private final InterServerBroadcastService interServerBroadcastService;

    @Data
    public static class WorldToken {
        private final String token;
        private final String tokenPass;
    }

    /**
     * 生成一个世界登录 token，广播给所有 GameServer，并返回给调用者。
     *
     * @return WorldToken(token, tokenPass)
     */
    public WorldToken generateAndBroadcastToken() {
        // 1) 简单起见，使用随机 UUID 作为 token / tokenPass
        String token     = UUID.randomUUID().toString().replace("-", "");
        String tokenPass = UUID.randomUUID().toString().replace("-", "");

        // 2) 本进程内记录一次（Login 或 Game 都可以调用）
        netServer.addWorldConnectAllowance(token, tokenPass);

        // 3) 构造 Net* 包，广播到所有 GameServer
        PacketNetPlayerWorldToken netPacket = new PacketNetPlayerWorldToken();
        netPacket.setPktHeader(PacketHeader.PKTHDR_NetPlayerWorldToken);
        netPacket.setToken(token);
        netPacket.setTokenPass(tokenPass);

        interServerBroadcastService.broadcastPacket(netPacket);

        log.info("Generated world token={}, tokenPass={} and broadcast to games", token, tokenPass);

        return new WorldToken(token, tokenPass);
    }
}