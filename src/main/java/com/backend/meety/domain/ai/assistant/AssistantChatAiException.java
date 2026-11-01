package com.backend.meety.domain.ai.assistant;

public class AssistantChatAiException extends RuntimeException {
    private final String failureCode;

    public AssistantChatAiException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public AssistantChatAiException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
