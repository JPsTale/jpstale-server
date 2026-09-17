package org.jpstale.server.core;

import org.jpstale.server.common.struct.packets.PacketNetPlayerWorldToken;

/**
 * 登录服侧 Net* 包处理接口。
 *
 * 对应 C++：
 * - Server/server/netserver.cpp
 * - Server/server/packetserver.cpp 中所有 PKTHDR_Net* 分支（LoginServer 相关部分）。
 */
public interface NetServer {

    void onNetIdentifier(Object packet);

    void onNetUsersOnline(Object packet);

    void onNetClan(Object packet);

    void onNetPlayDataEx(Object packet);

    void onNetQuestUpdateDataPart(Object packet);

    void onNetGiveExp(Object packet);

    void onNetPlayerGoldDiff(Object packet);

    void onNetPlayerItemPut(Object packet);

    void onNetPlayerThrow(Object packet);

    // ===== 世界登录 Token 相关 =====
    /**
     * 登录服：生成一个世界登录 Token 后调用，
     * 等价于 C++ NetServer::AddWorldConnectAllowance。
     */
    void addWorldConnectAllowance(String token, String tokenPass);
    /**
     * GameServer：客户端尝试世界登录时调用，
     * 校验并消费一次性 Token，等价于 C++ UsePlayerWorldLoginToken。
     *
     * @return true = Token 合法且已标记为已使用；false = 不存在或密码不匹配。
     */
    boolean usePlayerWorldLoginToken(String token, String tokenPass);
}

