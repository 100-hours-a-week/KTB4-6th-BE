package com.backend.meety.global.security;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            log.debug("요청에 쿠키가 없습니다. uri={}", request.getRequestURI());
            return null;
        }
        for (Cookie cookie : cookies) {
            if (TokenConstants.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        log.info("accessToken 쿠키가 없습니다. uri={}, cookieNames={}",
                request.getRequestURI(), Arrays.stream(cookies).map(Cookie::getName).toList());
        return null;
    }

    private void authenticate(HttpServletRequest request, String token) {
        if (accessTokenBlacklist.contains(token)) {
            request.setAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE, AuthErrorCode.INVALID_ACCESS_TOKEN);
            return;
        }
        try {
            Claims claims = jwtTokenProvider.validateAndDecode(token);
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get(TokenConstants.ROLE_CLAIM, String.class);
            if (role == null) {
                request.setAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE, AuthErrorCode.INVALID_ACCESS_TOKEN);
                return;
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority(TokenConstants.ROLE_PREFIX + role)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            request.setAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE, AuthErrorCode.EXPIRED_ACCESS_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE, AuthErrorCode.INVALID_ACCESS_TOKEN);
        }
    }
}
