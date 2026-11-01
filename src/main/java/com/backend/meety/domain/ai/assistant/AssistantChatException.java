package com.backend.meety.domain.ai.assistant;

import com.backend.meety.global.exception.BusinessException;

public class AssistantChatException extends BusinessException {
    public AssistantChatException(AssistantChatErrorCode errorCode) {
        super(errorCode);
    }
}
