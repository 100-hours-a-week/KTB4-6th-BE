package com.backend.meety.domain.dashboard.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DashboardErrorCode implements BaseCode {

    INVALID_DASHBOARD_PERIOD(HttpStatus.BAD_REQUEST,
            "INVALID_DASHBOARD_PERIOD", "대시보드 조회 기간은 7, 14, 21, 28일만 가능합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
