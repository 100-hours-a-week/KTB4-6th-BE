package com.backend.meety.domain.home.exception;

import com.backend.meety.global.exception.BusinessException;

public class HomeException extends BusinessException {

    public HomeException(HomeErrorCode errorCode) {
        super(errorCode);
    }
}
