package com.backend.meety.global.security;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.global.response.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        AuthErrorCode errorCode = resolveErrorCode(request);
        log.warn("인증에 실패했습니다. method={}, uri={}, accessTokenCookie={}, errorCode={}",
                request.getMethod(), request.getRequestURI(), hasAccessTokenCookie(request), errorCode);
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }

    private boolean hasAccessTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        return Arrays.stream(cookies)
                .anyMatch(cookie -> TokenConstants.ACCESS_TOKEN_COOKIE.equals(cookie.getName()));
    }

    private AuthErrorCode resolveErrorCode(HttpServletRequest request) {
        if (request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE) instanceof AuthErrorCode errorCode) {
            return errorCode;
        }
        return AuthErrorCode.AUTHENTICATION_REQUIRED;
    }
}
