package com.backend.meety.global.security;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.global.response.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        AuthErrorCode errorCode = resolveErrorCode(request);
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }

    private AuthErrorCode resolveErrorCode(HttpServletRequest request) {
        if (request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE) instanceof AuthErrorCode errorCode) {
            return errorCode;
        }
        return AuthErrorCode.AUTHENTICATION_REQUIRED;
    }
}
