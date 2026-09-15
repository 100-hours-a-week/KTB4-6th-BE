package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.user.entity.UserRole;
import com.backend.meety.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));

    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(PROPERTIES, Clock.systemDefaultZone());

    @Test
    @DisplayName("발급한 토큰을 검증하면 클레임이 올바르게 복원된다")
    void createAndDecode() {
        String token = jwtTokenProvider.createAccessToken(1L);

        Claims claims = jwtTokenProvider.validateAndDecode(token);

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get(TokenConstants.ROLE_CLAIM, String.class)).isEqualTo(UserRole.USER.name());
        assertThat(Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant()))
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 검증에 실패한다")
    void rejectForgedSignature() {
        JwtTokenProvider otherKeyProvider = new JwtTokenProvider(new JwtProperties(
                "another-jwt-secret-key-32bytes-!!!!", Duration.ofMinutes(30), Duration.ofDays(14)),
                Clock.systemDefaultZone());
        String forgedToken = otherKeyProvider.createAccessToken(1L);

        assertThatThrownBy(() -> jwtTokenProvider.validateAndDecode(forgedToken))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 검증에 실패한다")
    void rejectExpiredToken() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(40)), ZoneOffset.UTC);
        JwtTokenProvider pastProvider = new JwtTokenProvider(PROPERTIES, past);
        String expiredToken = pastProvider.createAccessToken(1L);

        assertThatThrownBy(() -> jwtTokenProvider.validateAndDecode(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("형식이 잘못된 토큰은 검증에 실패한다")
    void rejectMalformedToken() {
        assertThatThrownBy(() -> jwtTokenProvider.validateAndDecode("not-a-jwt"))
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test
    @DisplayName("secret이 32바이트 미만이면 생성에 실패한다")
    void rejectShortSecret() {
        JwtProperties shortSecret = new JwtProperties("too-short", Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtTokenProvider(shortSecret, Clock.systemDefaultZone()))
                .isInstanceOf(WeakKeyException.class);
    }
}
