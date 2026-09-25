package com.backend.meety.domain.meeting.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SummaryErrorCode implements BaseCode {

    MEETING_NOT_COMPLETED(HttpStatus.CONFLICT, "MEETING_NOT_COMPLETED", "종료된 회의만 요약할 수 있습니다."),
    TRANSCRIPT_EMPTY(HttpStatus.CONFLICT, "TRANSCRIPT_EMPTY", "전사 내용이 없어 요약할 수 없습니다."),
    SUMMARY_ALREADY_PROCESSING(HttpStatus.CONFLICT, "SUMMARY_ALREADY_PROCESSING", "이미 생성 중인 요약이 있습니다."),
    SUMMARY_NOT_FOUND(HttpStatus.NOT_FOUND, "SUMMARY_NOT_FOUND", "요약을 찾을 수 없습니다."),
    SUMMARY_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "SUMMARY_REQUEST_FAILED", "회의 요약 생성 요청에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
