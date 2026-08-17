package org.jpstale.server.game.validation;

import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.MessageProto;

/**
 * 验证器接口
 * 所有输入验证器都需要实现此接口
 */
public interface InputValidator {

    /**
     * 验证输入是否合法
     *
     * @param session 玩家会话
     * @param message 客户端消息
     * @return 验证结果
     */
    ValidationResult validate(PlayerSession session, MessageProto.ClientMessage message);

    /**
     * 获取验证器支持的消息类型字段号
     */
    int getSupportedMessageType();
}
