package org.jpstale.server.game.validator;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.validation.InputValidator;
import org.jpstale.server.game.validation.ValidationResult;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.stereotype.Component;

/**
 * 移动速度验证器
 * 检查玩家移动速度是否超过最大速度
 */
@Slf4j
@Component
public class MovementSpeedValidator implements InputValidator {

    private static final float SPEED_TOLERANCE = 1.1f; // 10% 容差
    private static final float MAX_SPEED = 10.0f; // 默认最大速度（后续从玩家属性获取）

    @Override
    public int getSupportedMessageType() {
        return MessageProto.ClientMessage.PLAYER_MOVE_FIELD_NUMBER;
    }

    @Override
    public ValidationResult validate(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_PlayerMove move = message.getPlayerMove();

        // TODO: 获取玩家上次移动时间和位置，计算实际速度
        // 暂时简单验证位置是否有效
        if (move.getNewPosition() == null) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Invalid position");
        }

        // 检查坐标是否在合理范围内
        float x = move.getNewPosition().getX();
        float y = move.getNewPosition().getY();
        float z = move.getNewPosition().getZ();

        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "NaN position");
        }

        if (Float.isInfinite(x) || Float.isInfinite(y) || Float.isInfinite(z)) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Infinite position");
        }

        // TODO: 实现完整的速度验证逻辑
        // 1. 获取玩家上次移动时间
        // 2. 计算时间差
        // 3. 计算移动距离
        // 4. 计算速度 = 距离 / 时间
        // 5. 检查速度是否超过最大速度 * 容差

        return ValidationResult.success();
    }
}
