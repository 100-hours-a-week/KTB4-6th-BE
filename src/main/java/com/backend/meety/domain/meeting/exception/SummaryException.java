package com.backend.meety.domain.meeting.exception;

import com.backend.meety.global.exception.BusinessException;

public class SummaryException extends BusinessException {

    public SummaryException(SummaryErrorCode errorCode) {
        super(errorCode);
    }
}
