package com.backend.meety.domain.team.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TeamErrorCode implements BaseCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    TEAM_NOT_FOUND(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀을 찾을 수 없습니다."),
    TEAM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "TEAM_ACCESS_DENIED", "팀에 접근할 권한이 없습니다."),
    TEAM_LEADER_REQUIRED(HttpStatus.FORBIDDEN, "TEAM_LEADER_REQUIRED", "팀장만 요청할 수 있습니다."),
    ACTIVE_TEAM_ALREADY_EXISTS(HttpStatus.CONFLICT, "ACTIVE_TEAM_ALREADY_EXISTS", "이미 참여 중인 팀이 있습니다."),
    INVITATION_CODE_REGENERATION_LIMIT_EXCEEDED(HttpStatus.CONFLICT,
            "INVITATION_CODE_REGENERATION_LIMIT_EXCEEDED", "초대 코드는 하루에 한 번만 생성할 수 있습니다."),
    TEAM_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TEAM_CREATE_FAILED", "팀을 생성하지 못했습니다. 다시 시도해주세요."),
    INVITATION_CODE_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "INVITATION_CODE_CREATE_FAILED", "초대 코드를 생성하지 못했습니다. 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
