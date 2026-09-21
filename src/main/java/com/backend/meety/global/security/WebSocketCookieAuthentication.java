package com.backend.meety.global.security;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebSocketCookieAuthentication {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;

    public Long authenticate(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }
        return authenticate(servletRequest.getServletRequest());
    }

    private Long authenticate(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (accessTokenBlacklist.contains(token)) {
            throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN);
        }
        try {
            Claims claims = jwtTokenProvider.validateAndDecode(token);
            return Long.valueOf(claims.getSubject());
        } catch (ExpiredJwtException e) {
            throw new AuthException(AuthErrorCode.EXPIRED_ACCESS_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (TokenConstants.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
