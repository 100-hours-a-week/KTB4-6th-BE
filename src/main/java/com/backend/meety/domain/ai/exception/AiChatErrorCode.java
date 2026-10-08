package com.backend.meety.domain.ai.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiChatErrorCode implements BaseCode {

    INVALID_QUESTION_LENGTH(HttpStatus.BAD_REQUEST, "INVALID_QUESTION_LENGTH", "질문은 1자 이상 1000자 이하여야 합니다."),
    INVALID_INPUT_TYPE(HttpStatus.BAD_REQUEST, "INVALID_INPUT_TYPE", "지원하지 않는 입력 방식입니다."),
    MEETING_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "MEETING_NOT_IN_PROGRESS", "진행 중인 회의에서만 질문할 수 있습니다."),
    AI_MESSAGE_ALREADY_PROCESSING(HttpStatus.CONFLICT, "AI_MESSAGE_ALREADY_PROCESSING", "이전 질문의 답변이 완료된 후 질문할 수 있습니다."),
    AI_MESSAGE_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AI_MESSAGE_REQUEST_FAILED", "AI 질문 요청에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
