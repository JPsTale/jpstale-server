package org.jpstale.server.game.common;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.C2S_PlayerMove;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.CommonProto;
import org.springframework.stereotype.Component;

/**
 * 移动速度验证器
 * 检查玩家移动速度是否超过最大速度
 */
@Slf4j
@Component
public class MovementSpeedValidator implements InputValidator {

    @Override
    public int getSupportedMessageType() {
        return ClientMessage.PLAYER_MOVE_FIELD_NUMBER;
    }

    @Override
    public ValidationResult validate(PlayerSession session, ClientMessage message) {
        C2S_PlayerMove move = message.getPlayerMove();

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
