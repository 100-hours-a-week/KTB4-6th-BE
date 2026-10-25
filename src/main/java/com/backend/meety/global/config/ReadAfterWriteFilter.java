package com.backend.meety.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class ReadAfterWriteFilter extends OncePerRequestFilter {

    static final String COOKIE_NAME = "read-master-until";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String INTERNAL_API_PREFIX = "/internal/";
    private static final String COOKIE_PATH = "/";
    private static final String SAME_SITE = "Lax";

    private final ReadAfterWriteProperties properties;
    private final Clock clock;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long now = clock.millis();
        boolean write = WRITE_METHODS.contains(request.getMethod());
        if (write) {
            response.addHeader(HttpHeaders.SET_COOKIE, readMasterCookie(now + properties.window().toMillis()));
        }
        if (write || request.getRequestURI().startsWith(INTERNAL_API_PREFIX) || hasValidCookie(request, now)) {
            ReadMasterContext.force();
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            ReadMasterContext.clear();
        }
    }

    private String readMasterCookie(long until) {
        return ResponseCookie.from(COOKIE_NAME, Long.toString(until))
                .maxAge(properties.window())
                .path(COOKIE_PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .build()
                .toString();
    }

    private boolean hasValidCookie(HttpServletRequest request, long now) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return isWithinWindow(cookie.getValue(), now);
            }
        }
        return false;
    }

    private boolean isWithinWindow(String value, long now) {
        try {
            long until = Long.parseLong(value);
            return now < until && until <= now + properties.window().toMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
