package org.jpstale.server.common.redis;

public interface RedisMsgListener {
    String getType();
    void onMessage(CommonMsg message);
}
