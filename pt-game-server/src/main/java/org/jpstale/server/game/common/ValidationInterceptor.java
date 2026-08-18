package org.jpstale.server.game.common;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证拦截器
 * 在消息处理前进行输入验证
 */
@Slf4j
@Component
public class ValidationInterceptor {

    private final Map<Integer, InputValidator> validators = new HashMap<>();

    @Autowired
    private List<InputValidator> validatorList;

    @PostConstruct
    public void init() {
        for (InputValidator validator : validatorList) {
            int messageType = validator.getSupportedMessageType();
            validators.put(messageType, validator);
            log.debug("Registered validator: {} for message type: {}", 
                validator.getClass().getSimpleName(), messageType);
        }
        log.info("ValidationInterceptor initialized with {} validators", validators.size());
    }

    /**
     * 验证消息
     */
    public ValidationResult validate(PlayerSession session, MessageProto.ClientMessage message) {
        int messageType = message.getPayloadCase().getNumber();
        InputValidator validator = validators.get(messageType);

        if (validator != null) {
            return validator.validate(session, message);
        }

        // 没有验证器，默认通过
        return ValidationResult.success();
    }

    /**
     * 检查是否有验证器
     */
    public boolean hasValidator(int messageType) {
        return validators.containsKey(messageType);
    }
}
