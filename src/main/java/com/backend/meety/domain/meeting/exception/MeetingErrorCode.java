package com.backend.meety.domain.meeting.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MeetingErrorCode implements BaseCode {

    TEAM_NOT_FOUND(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀을 찾을 수 없습니다."),
    TEAM_MEMBERSHIP_REQUIRED(HttpStatus.FORBIDDEN, "TEAM_MEMBERSHIP_REQUIRED", "활성 팀원만 회의를 생성할 수 있습니다."),
    DAILY_MEETING_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "DAILY_MEETING_LIMIT_EXCEEDED", "팀은 하루에 회의를 1개만 생성할 수 있습니다."),
    MEETING_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "MEETING_CREATE_FAILED", "회의 생성에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
