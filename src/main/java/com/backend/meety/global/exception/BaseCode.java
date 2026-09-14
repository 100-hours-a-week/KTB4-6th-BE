package com.backend.meety.global.exception;

import org.springframework.http.HttpStatus;

public interface BaseCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}
