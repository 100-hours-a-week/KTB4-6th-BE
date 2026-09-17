package com.backend.meety.domain.home.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum HomeErrorCode implements BaseCode {

    ACTIVE_TEAM_REQUIRED(HttpStatus.FORBIDDEN, "ACTIVE_TEAM_REQUIRED", "활성 팀이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
