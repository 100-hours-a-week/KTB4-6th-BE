package com.backend.meety.global.security;

import com.backend.meety.domain.user.entity.UserRole;
import com.backend.meety.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private static final UserRole DEFAULT_ROLE = UserRole.USER;

    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties jwtProperties, Clock clock) {
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .clock(() -> Date.from(clock.instant()))
                .build();
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    public String createAccessToken(Long userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.accessTokenValidity())))
                .claim(TokenConstants.ROLE_CLAIM, DEFAULT_ROLE.name())
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims validateAndDecode(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }
}
