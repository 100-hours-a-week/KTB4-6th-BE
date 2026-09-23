package com.backend.meety.domain.recording.exception;

import com.backend.meety.global.exception.BusinessException;

public class AudioFileException extends BusinessException {

    public AudioFileException(AudioFileErrorCode errorCode) {
        super(errorCode);
    }
}
