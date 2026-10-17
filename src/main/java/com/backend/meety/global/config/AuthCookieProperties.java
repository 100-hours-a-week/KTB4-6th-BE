package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AuthCookieProperties(String accessTokenCookieName) {
}
