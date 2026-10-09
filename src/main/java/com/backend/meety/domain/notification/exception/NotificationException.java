package com.backend.meety.domain.notification.exception;

import com.backend.meety.global.exception.BusinessException;

public class NotificationException extends BusinessException {

    public NotificationException(NotificationErrorCode errorCode) {
        super(errorCode);
    }
}
