package com.backend.meety.global.security;

public final class PermitAllUrls {

    public static final String[] URLS = {
            "/actuator/health",
            "/api/v1/auth/*/login"
    };

    private PermitAllUrls() {
    }
}
