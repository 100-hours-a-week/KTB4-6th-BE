package com.backend.meety.domain.ai.exception;

import com.backend.meety.global.exception.BusinessException;

public class AiChatException extends BusinessException {

    public AiChatException(AiChatErrorCode errorCode) {
        super(errorCode);
    }
}
