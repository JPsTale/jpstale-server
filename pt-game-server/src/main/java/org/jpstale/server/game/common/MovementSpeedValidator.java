package org.jpstale.server.game.common;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.common.InputValidator;
import org.jpstale.server.game.common.ValidationResult;
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

        // 客户端位置上权威：报文带 position{world float} + angle + mode。
        // 服务端层校验有限性；限速/防瞬移在核心 loop 消费时按 Δt 距离校验。
        if (move.getMode() < 0 || move.getMode() > 2) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Invalid move mode");
        }
        if (!move.hasPosition()) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Missing position");
        }
        float angle = move.getAngle();
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Invalid angle");
        }
        float x = move.getPosition().getX();
        float y = move.getPosition().getY();
        float z = move.getPosition().getZ();
        if (Float.isNaN(x) || Float.isInfinite(x)
            || Float.isNaN(y) || Float.isInfinite(y)
            || Float.isNaN(z) || Float.isInfinite(z)) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Invalid position");
        }
        return ValidationResult.success();
    }
}
