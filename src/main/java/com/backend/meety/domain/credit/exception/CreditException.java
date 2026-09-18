package com.backend.meety.domain.credit.exception;

import com.backend.meety.global.exception.BusinessException;

public class CreditException extends BusinessException {

    public CreditException(CreditErrorCode errorCode) {
        super(errorCode);
    }
}
