package org.jpstale.server.core;

import org.jpstale.server.common.struct.packets.Packet;

/**
 * 服务器间广播服务接口。
 * 供其他服务向所有连接的服务器广播数据包。
 */
public interface InterServerBroadcastService {

    /**
     * 向所有已连接的服务器广播数据包。
     *
     * @param packet 要广播的数据包
     */
    void broadcastPacket(Packet packet);
}