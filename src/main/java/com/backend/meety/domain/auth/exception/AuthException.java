package com.backend.meety.domain.auth.exception;

import com.backend.meety.global.exception.BusinessException;

public class AuthException extends BusinessException {

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode);
    }
}
