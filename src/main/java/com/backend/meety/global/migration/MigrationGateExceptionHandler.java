package com.backend.meety.global.migration;

import com.backend.meety.global.response.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice

@Order(-1)
public class MigrationGateExceptionHandler {
    @ExceptionHandler(MigrationGateUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(MigrationGateUnavailableException exception) {


        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(ApiResponse.error("MIGRATION_WRITE_UNAVAILABLE", exception.getMessage()));
    }
}
