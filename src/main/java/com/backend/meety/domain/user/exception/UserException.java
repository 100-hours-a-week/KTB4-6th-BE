package com.backend.meety.domain.user.exception;

import com.backend.meety.global.exception.BusinessException;

public class UserException extends BusinessException {

    public UserException(UserErrorCode errorCode) {
        super(errorCode);
    }
}
