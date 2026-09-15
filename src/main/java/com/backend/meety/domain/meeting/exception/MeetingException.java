package com.backend.meety.domain.meeting.exception;

import com.backend.meety.global.exception.BusinessException;

public class MeetingException extends BusinessException {

    public MeetingException(MeetingErrorCode errorCode) {
        super(errorCode);
    }
}
