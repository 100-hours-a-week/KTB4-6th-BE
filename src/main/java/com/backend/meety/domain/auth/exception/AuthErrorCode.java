package com.backend.meety.domain.auth.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseCode {

    INVALID_AUTHORIZATION_CODE(HttpStatus.UNAUTHORIZED, "INVALID_AUTHORIZATION_CODE", "인가 코드를 확인해주세요."),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OAUTH_PROVIDER", "지원하지 않는 소셜 로그인입니다."),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "KAKAO_AUTH_FAILED", "카카오 로그인에 실패했습니다. 다시 시도해주세요."),
    AUTH_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_PROCESSING_FAILED", "로그인 처리 중 오류가 발생했습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "유효하지 않은 리프레시 토큰입니다."),
    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_REFRESH_TOKEN", "만료된 리프레시 토큰입니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "유효하지 않은 토큰입니다."),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_ACCESS_TOKEN", "만료된 토큰입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
