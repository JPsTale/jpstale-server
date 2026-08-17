package org.jpstale.server.game.validation;

import lombok.Data;
import org.jpstale.server.proto.base.CommonProto;

/**
 * 验证结果
 */
@Data
public class ValidationResult {

    private final boolean valid;
    private final CommonProto.ErrorCode errorCode;
    private final String errorMessage;

    private ValidationResult(boolean valid, CommonProto.ErrorCode errorCode, String errorMessage) {
        this.valid = valid;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * 验证成功
     */
    public static ValidationResult success() {
        return new ValidationResult(true, null, null);
    }

    /**
     * 验证失败
     */
    public static ValidationResult fail(CommonProto.ErrorCode errorCode, String errorMessage) {
        return new ValidationResult(false, errorCode, errorMessage);
    }
}
