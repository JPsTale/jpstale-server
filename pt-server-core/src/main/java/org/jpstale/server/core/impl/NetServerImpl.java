package org.jpstale.server.core.impl;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.core.NetServer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NetServer 默认实现（Login/Game 共有的一套逻辑）。
 *
 * 目前：
 * - Net* 回调大部分只是留日志（占位，方便后续按 C++ 对照补齐）。
 * - 世界登录 Token 逻辑已经完整实现，等价于 C++ 的
 *   m_PlayerWorldLoginToken + UsePlayerWorldLoginToken。
 */
@Slf4j
@Service
public class NetServerImpl implements NetServer {

    /**
     * 等价于 C++ 的 m_PlayerWorldLoginToken：
     * key: token, value: tokenPass
     *
     * Login / Game 进程各自持有一份，仅用于本进程内：
     * - Login 生成 token 后会调用 addWorldConnectAllowance 存入；
     * - Game 在收到 Net* 包或本地生成 token 时也会存入；
     * - 校验通过时 usePlayerWorldLoginToken 会一次性删除。
     */
    private final Map<String, String> playerWorldLoginToken = new ConcurrentHashMap<>();

    // =============== C++ NetServer::OnReceivePacket 中各种 Net* 分支的占位回调 ===============

    @Override
    public void onNetIdentifier(Object packet) {
        log.trace("onNetIdentifier (TODO) packet={}", packet);
    }

    @Override
    public void onNetUsersOnline(Object packet) {
        log.trace("onNetUsersOnline (TODO) packet={}", packet);
    }

    @Override
    public void onNetClan(Object packet) {
        log.trace("onNetClan (TODO) packet={}", packet);
    }

    @Override
    public void onNetPlayDataEx(Object packet) {
        log.trace("onNetPlayDataEx (TODO) packet={}", packet);
    }

    @Override
    public void onNetQuestUpdateDataPart(Object packet) {
        log.trace("onNetQuestUpdateDataPart (TODO) packet={}", packet);
    }

    @Override
    public void onNetGiveExp(Object packet) {
        log.trace("onNetGiveExp (TODO) packet={}", packet);
    }

    @Override
    public void onNetPlayerGoldDiff(Object packet) {
        log.trace("onNetPlayerGoldDiff (TODO) packet={}", packet);
    }

    @Override
    public void onNetPlayerItemPut(Object packet) {
        log.trace("onNetPlayerItemPut (TODO) packet={}", packet);
    }

    @Override
    public void onNetPlayerThrow(Object packet) {
        log.trace("onNetPlayerThrow (TODO) packet={}", packet);
    }

    // ========================= 世界登录 Token 逻辑 =========================

    @Override
    public void addWorldConnectAllowance(String token, String tokenPass) {
        if (token == null || tokenPass == null) {
            log.warn("addWorldConnectAllowance with null token/tokenPass");
            return;
        }
        playerWorldLoginToken.put(token, tokenPass);
        log.debug("AddWorldConnectAllowance token={} (size={})",
                token, playerWorldLoginToken.size());
    }

    @Override
    public boolean usePlayerWorldLoginToken(String token, String tokenPass) {
        if (token == null || tokenPass == null) {
            return false;
        }
        String expected = playerWorldLoginToken.get(token);
        if (expected == null) {
            return false;
        }
        if (!expected.equals(tokenPass)) {
            return false;
        }
        // 一次性消费
        playerWorldLoginToken.remove(token);
        log.debug("UsePlayerWorldLoginToken success token={}", token);
        return true;
    }
}