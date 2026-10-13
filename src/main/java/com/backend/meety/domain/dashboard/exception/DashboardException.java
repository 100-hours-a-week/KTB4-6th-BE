package com.backend.meety.domain.dashboard.exception;

import com.backend.meety.global.exception.BusinessException;

public class DashboardException extends BusinessException {

    public DashboardException(DashboardErrorCode errorCode) {
        super(errorCode);
    }
}
