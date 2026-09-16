package com.backend.meety.global.security;

public final class PermitAllUrls {

    public static final String[] URLS = {
            "/actuator/health",
            "/api/v1/auth/*/login",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/logout"
    };

    private PermitAllUrls() {
    }
}
