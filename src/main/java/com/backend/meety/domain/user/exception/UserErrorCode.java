package com.backend.meety.domain.user.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements BaseCode {

    USER_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "USER_DELETE_FAILED", "회원 탈퇴에 실패했습니다."),
    VOICE_PROFILE_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "VOICE_PROFILE_CONSENT_REQUIRED", "음성 수집에 동의해주세요."),
    VOICE_PROFILE_EXTRACTION_FAILED(HttpStatus.BAD_GATEWAY, "VOICE_PROFILE_EXTRACTION_FAILED",
            "음성 등록에 실패했습니다. 다시 녹음해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
