package com.backend.meety.domain.ai.assistant;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AssistantChatErrorCode implements BaseCode {
    ACTIVE_REQUEST_EXISTS(HttpStatus.CONFLICT, "ASSISTANT_CHAT_ALREADY_PROCESSING", "이 대화에서 처리 중인 요청이 있습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 요청 키가 다른 질문에 사용되었습니다."),
    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "ASSISTANT_CHAT_NOT_FOUND", "대화 또는 요청을 찾을 수 없습니다."),
    CHAT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "ASSISTANT_CHAT_ACCESS_DENIED", "대화에 접근할 권한이 없습니다."),
    DRAFT_NOT_CONFIRMED(HttpStatus.CONFLICT, "ASSISTANT_MEETING_DRAFT_NOT_CONFIRMED", "유효한 사용자 확인 회의 초안이 아닙니다."),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key가 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
