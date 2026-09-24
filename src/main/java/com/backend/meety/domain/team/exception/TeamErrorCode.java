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
    TEAM_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "TEAM_MEMBER_NOT_FOUND", "팀원을 찾을 수 없습니다."),
    TEAM_BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "TEAM_BLOCK_NOT_FOUND", "차단 정보를 찾을 수 없습니다."),
    INVITATION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "INVITATION_CODE_NOT_FOUND", "유효하지 않은 초대 코드입니다."),
    TEAM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "TEAM_ACCESS_DENIED", "팀에 접근할 권한이 없습니다."),
    TEAM_LEADER_REQUIRED(HttpStatus.FORBIDDEN, "TEAM_LEADER_REQUIRED", "팀장만 요청할 수 있습니다."),
    TEAM_MEMBERSHIP_REQUIRED(HttpStatus.FORBIDDEN, "TEAM_MEMBERSHIP_REQUIRED", "현재 참여 중인 팀이 아닙니다."),
    TEAM_BLOCKED_USER(HttpStatus.FORBIDDEN, "TEAM_BLOCKED_USER", "해당 팀에 참여할 수 없습니다."),
    ACTIVE_TEAM_ALREADY_EXISTS(HttpStatus.CONFLICT, "ACTIVE_TEAM_ALREADY_EXISTS", "이미 참여 중인 팀이 있습니다."),
    LEADER_CANNOT_LEAVE_TEAM(HttpStatus.CONFLICT, "LEADER_CANNOT_LEAVE_TEAM", "팀장 권한을 위임하거나 팀을 삭제해주세요."),
    LEADER_MUST_TRANSFER_OR_DELETE_TEAM(HttpStatus.CONFLICT,
            "LEADER_MUST_TRANSFER_OR_DELETE_TEAM", "팀장은 탈퇴하기 전 팀장 위임이나 팀을 삭제해야 합니다."),
    CANNOT_KICK_SELF(HttpStatus.CONFLICT, "CANNOT_KICK_SELF", "자기 자신을 강퇴할 수 없습니다."),
    TEAM_MEMBER_NOT_ACTIVE(HttpStatus.CONFLICT, "TEAM_MEMBER_NOT_ACTIVE", "활성 상태의 팀원이 아닙니다."),
    TARGET_TEAM_MEMBER_NOT_ACTIVE(HttpStatus.CONFLICT,
            "TARGET_TEAM_MEMBER_NOT_ACTIVE", "활성 팀원에게만 팀장을 위임할 수 있습니다."),
    LEADER_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "LEADER_ALREADY_ASSIGNED", "이미 해당 팀원이 팀장입니다."),
    TEAM_CREATE_DAILY_LIMIT_EXCEEDED(HttpStatus.CONFLICT,
            "TEAM_CREATE_DAILY_LIMIT_EXCEEDED", "팀에서 나간 당일에는 팀을 만들 수 없습니다. 다음 날 다시 시도해주세요."),
    TEAM_MEMBER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "TEAM_MEMBER_LIMIT_EXCEEDED", "참여 인원이 가득 찼습니다."),
    DISPLAY_NAME_DUPLICATED(HttpStatus.CONFLICT, "DISPLAY_NAME_DUPLICATED", "이미 사용 중인 이름입니다."),
    INVITATION_CODE_REGENERATION_LIMIT_EXCEEDED(HttpStatus.CONFLICT,
            "INVITATION_CODE_REGENERATION_LIMIT_EXCEEDED", "초대 코드는 하루에 한 번만 생성할 수 있습니다."),
    TEAM_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TEAM_CREATE_FAILED", "팀을 생성하지 못했습니다. 다시 시도해주세요."),
    INVITATION_CODE_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "INVITATION_CODE_CREATE_FAILED", "초대 코드를 생성하지 못했습니다. 다시 시도해주세요."),
    TEAM_MEMBERSHIP_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "TEAM_MEMBERSHIP_CREATE_FAILED", "팀 참여 처리에 실패했습니다."),
    TEAM_MEMBER_LOOKUP_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "TEAM_MEMBER_LOOKUP_FAILED", "팀원 목록을 조회하지 못했습니다."),
    TEAM_LEAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TEAM_LEAVE_FAILED", "팀 나가기에 실패했습니다."),
    TEAM_MEMBER_KICK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "TEAM_MEMBER_KICK_FAILED", "팀원 강퇴에 실패했습니다."),
    TEAM_BLOCK_LIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "TEAM_BLOCK_LIST_FAILED", "차단 목록을 조회하지 못했습니다."),
    TEAM_BLOCK_RELEASE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "TEAM_BLOCK_RELEASE_FAILED", "차단 해제에 실패했습니다."),
    TEAM_LEADER_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
            "TEAM_LEADER_UPDATE_FAILED", "팀장 위임에 실패했습니다."),
    TEAM_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TEAM_DELETE_FAILED", "팀을 삭제하지 못했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
