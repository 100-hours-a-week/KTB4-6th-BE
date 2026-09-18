package com.backend.meety.domain.recording.exception;

import com.backend.meety.global.exception.BusinessException;

public class RecordingException extends BusinessException {

    public RecordingException(RecordingErrorCode errorCode) {
        super(errorCode);
    }
}
