package com.backend.meety.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.backend.meety.domain.auth.dto.RefreshTokenRotation;
import com.backend.meety.domain.auth.entity.RefreshToken;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.repository.RefreshTokenRepository;
import com.backend.meety.global.config.JwtProperties;
import com.backend.meety.global.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(
                refreshTokenRepository, PROPERTIES, Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("활성 RT를 로테이션하면 기존 RT를 사용 처리하고 새 RT를 발급한다")
    void rotate() {
        RefreshToken activeToken = RefreshToken.of(1L, "hash", LocalDateTime.now().plusDays(1));
        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(activeToken));
        given(refreshTokenRepository.markUsedIfActive(anyString(), any())).willReturn(1);

        RefreshTokenRotation rotation = refreshTokenService.rotate("raw-token");

        assertThat(rotation.userId()).isEqualTo(1L);
        assertThat(rotation.refreshToken()).isNotBlank();
        then(refreshTokenRepository).should().save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("저장소에 없는 RT는 재발급이 거부된다")
    void rejectUnknownToken() {
        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("unknown-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("만료된 RT는 재발급이 거부된다")
    void rejectExpiredToken() {
        RefreshToken expiredToken = RefreshToken.of(1L, "hash", LocalDateTime.now().minusMinutes(1));
        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> refreshTokenService.rotate("expired-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EXPIRED_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("로그아웃 시 RT를 사용 처리하여 무효화한다")
    void revoke() {
        refreshTokenService.revoke("raw-token");

        then(refreshTokenRepository).should().markUsedIfActive(anyString(), any());
    }

    @Test
    @DisplayName("이미 사용됐거나 동시 경합으로 사용 처리에 실패한 RT는 거부된다")
    void rejectUsedOrRacedToken() {
        RefreshToken activeToken = RefreshToken.of(1L, "hash", LocalDateTime.now().plusDays(1));
        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(activeToken));
        given(refreshTokenRepository.markUsedIfActive(anyString(), any())).willReturn(0);

        assertThatThrownBy(() -> refreshTokenService.rotate("raced-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }
}
