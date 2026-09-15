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
    DAILY_MEETING_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "DAILY_MEETING_LIMIT_EXCEEDED", "팀은 하루에 회의를 5개만 생성할 수 있습니다."),
    MEETING_NOT_FOUND(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "회의를 찾을 수 없습니다."),
    MEETING_ACCESS_DENIED(HttpStatus.FORBIDDEN, "MEETING_ACCESS_DENIED", "회의에 접근할 권한이 없습니다."),
    MEETING_PARTICIPATION_NOT_ALLOWED(HttpStatus.CONFLICT, "MEETING_PARTICIPATION_NOT_ALLOWED", "현재 회의 상태에서는 참여자 요청을 처리할 수 없습니다."),
    ALREADY_PARTICIPATING(HttpStatus.CONFLICT, "ALREADY_PARTICIPATING", "이미 회의에 참여 중입니다."),
    PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "회의 참석자를 찾을 수 없습니다."),
    ALREADY_LEFT(HttpStatus.CONFLICT, "ALREADY_LEFT", "이미 회의에서 나간 참석자입니다."),
    PARTICIPANT_STATUS_CONFLICT(HttpStatus.CONFLICT, "PARTICIPANT_STATUS_CONFLICT", "현재 참석자 상태에서는 요청을 처리할 수 없습니다."),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "조회 시작일은 종료일보다 이후일 수 없습니다."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "커서 값이 올바르지 않습니다."),
    INVALID_PAGE_SIZE(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE", "페이지 크기가 올바르지 않습니다."),
    INVALID_MEETING_KEYWORD(HttpStatus.BAD_REQUEST, "INVALID_MEETING_KEYWORD", "검색어가 올바르지 않습니다."),
    MEETING_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "MEETING_CREATE_FAILED", "회의 생성에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
