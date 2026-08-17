package org.jpstale.server.game.network;

import org.jpstale.server.proto.base.MessageProto;

import java.util.List;

/**
 * 消息发送器接口
 * 支持多种通信模式
 */
public interface MessageSender {

    /**
     * 单播：发送给单个玩家
     */
    void sendToPlayer(long playerId, MessageProto.ServerMessage message);

    /**
     * 广播：发送给地图所有玩家
     */
    void broadcastToMap(int mapId, MessageProto.ServerMessage message);

    /**
     * 区域广播：发送给视野内玩家
     */
    void broadcastToArea(int mapId, float centerX, float centerZ, float range, MessageProto.ServerMessage message);

    /**
     * 组播：发送给组队/公会成员
     */
    void multicast(List<Long> playerIds, MessageProto.ServerMessage message);

    /**
     * 全服广播：发送给所有在线玩家
     */
    void broadcastToAll(MessageProto.ServerMessage message);
}
