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

        // 新协议：客户端只上报移动意图（angle+mode），不采位置 → 无需校验 position。
        // mode ∈ {0 IDLE, 1 WALK, 2 RUN}；angle 需为有限弧度。
        if (move.getMode() < 0 || move.getMode() > 2) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Invalid move mode");
        }

        float angle = move.getAngle();
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            return ValidationResult.fail(CommonProto.ErrorCode.POSITION_INVALID, "Invalid angle");
        }

        // TODO: 实现基于 tick 权威位移的速度验证（防加速）
        // 1. 记录上次意图时间/方向
        // 2. 对比 tickPlayers 计算的理论位移
        // 3. 检查是否超过 EU 步长上限 * 容差

        return ValidationResult.success();
    }
}
