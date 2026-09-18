package com.backend.meety.domain.credit.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CreditErrorCode implements BaseCode {

    INSUFFICIENT_CREDIT(HttpStatus.CONFLICT, "INSUFFICIENT_CREDIT", "크레딧이 부족합니다."),
    TEAM_CREDIT_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "TEAM_CREDIT_NOT_FOUND", "팀 크레딧 정보를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
