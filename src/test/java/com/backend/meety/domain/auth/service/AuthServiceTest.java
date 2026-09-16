package com.backend.meety.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.dto.OAuthUserInfo;
import com.backend.meety.domain.auth.dto.RefreshTokenRotation;
import com.backend.meety.domain.auth.dto.TokenRefreshResult;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.service.UserAccountService;
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.security.JwtTokenProvider;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PROVIDER = "kakao";
    private static final String PROVIDER_USER_ID = "123456789";

    @Mock
    private OAuthProviderClient kakaoOAuthClient;

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private com.backend.meety.global.security.AccessTokenBlacklist accessTokenBlacklist;

    @Mock
    private User user;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(Map.of(PROVIDER, kakaoOAuthClient),
                userAccountService, refreshTokenService, jwtTokenProvider, accessTokenBlacklist);
    }

    @Test
    @DisplayName("로그아웃하면 RT를 무효화하고 AT를 블랙리스트에 등록한다")
    void logout() {
        authService.logout("access-token", "refresh-token");

        then(refreshTokenService).should().revoke("refresh-token");
        then(accessTokenBlacklist).should().register("access-token");
    }

    @Test
    @DisplayName("로그인에 성공하면 userId와 AT/RT를 반환한다")
    void login() {
        given(kakaoOAuthClient.fetchUserInfo("auth-code"))
                .willReturn(new OAuthUserInfo(PROVIDER, PROVIDER_USER_ID));
        given(userAccountService.findOrCreate(PROVIDER, PROVIDER_USER_ID)).willReturn(user);
        given(user.getId()).willReturn(1L);
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("access-token");
        given(refreshTokenService.issue(1L)).willReturn("refresh-token");

        LoginResponse response = authService.login(PROVIDER, "auth-code");

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("카카오 인증에 실패하면 예외가 전파된다")
    void loginFailsOnKakaoAuthFailure() {
        given(kakaoOAuthClient.fetchUserInfo("bad-code"))
                .willThrow(new AuthException(AuthErrorCode.KAKAO_AUTH_FAILED));

        assertThatThrownBy(() -> authService.login(PROVIDER, "bad-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_AUTH_FAILED));
    }

    @Test
    @DisplayName("토큰 재발급에 성공하면 새 AT와 RT를 반환한다")
    void refresh() {
        given(refreshTokenService.rotate("raw-rt")).willReturn(new RefreshTokenRotation(1L, "new-rt"));
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("new-at");

        TokenRefreshResult result = authService.refresh("raw-rt");

        assertThat(result.accessToken()).isEqualTo("new-at");
        assertThat(result.refreshToken()).isEqualTo("new-rt");
    }

    @Test
    @DisplayName("지원하지 않는 provider면 로그인이 거부된다")
    void loginFailsOnUnsupportedProvider() {
        assertThatThrownBy(() -> authService.login("google", "auth-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER));
    }
}
