package com.backend.meety.domain.transcript.exception;

import com.backend.meety.global.exception.BusinessException;

public class TranscriptException extends BusinessException {

    public TranscriptException(TranscriptErrorCode errorCode) {
        super(errorCode);
    }
}
