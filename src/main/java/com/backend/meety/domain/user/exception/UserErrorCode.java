package com.backend.meety.domain.user.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements BaseCode {

    USER_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "USER_DELETE_FAILED", "회원 탈퇴에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
