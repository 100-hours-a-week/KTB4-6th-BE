package com.backend.meety.domain.team.exception;

import com.backend.meety.global.exception.BusinessException;

public class TeamException extends BusinessException {

    public TeamException(TeamErrorCode errorCode) {
        super(errorCode);
    }
}
