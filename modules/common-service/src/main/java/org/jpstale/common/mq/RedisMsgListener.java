package org.jpstale.common.mq;

public interface RedisMsgListener {
    String getType();
    void onMessage(CommonMsg message);
}
