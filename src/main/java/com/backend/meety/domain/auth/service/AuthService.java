package com.backend.meety.domain.auth.service;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.dto.OAuthUserInfo;
import com.backend.meety.domain.auth.dto.RefreshTokenRotation;
import com.backend.meety.domain.auth.dto.TokenRefreshResult;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.service.UserAccountService;
import com.backend.meety.global.config.JwtProperties;
import com.backend.meety.global.security.AccessTokenBlacklist;
import com.backend.meety.global.security.JwtTokenProvider;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final Map<String, OAuthProviderClient> oAuthProviderClients;
    private final UserAccountService userAccountService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final JwtProperties jwtProperties;

    public LoginResponse login(String provider, String authorizationCode) {
        OAuthProviderClient client = findClient(provider);
        OAuthUserInfo userInfo = client.fetchUserInfo(authorizationCode);
        User user = userAccountService.findOrCreate(userInfo.provider(), userInfo.providerUserId());
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = refreshTokenService.issue(user.getId());
        return new LoginResponse(user.getId(), accessToken, refreshToken,
                jwtProperties.accessTokenValidity().toSeconds(),
                jwtProperties.refreshTokenValidity().toSeconds());
    }

    public void logout(String accessToken, String refreshToken) {
        refreshTokenService.revoke(refreshToken);
        accessTokenBlacklist.register(accessToken);
    }

    public TokenRefreshResult refresh(String refreshToken) {
        RefreshTokenRotation rotation = refreshTokenService.rotate(refreshToken);
        String accessToken = jwtTokenProvider.createAccessToken(rotation.userId());
        return new TokenRefreshResult(accessToken, rotation.refreshToken(),
                jwtProperties.accessTokenValidity().toSeconds(),
                jwtProperties.refreshTokenValidity().toSeconds());
    }

    private OAuthProviderClient findClient(String provider) {
        return Optional.ofNullable(oAuthProviderClients.get(provider))
                .orElseThrow(() -> new AuthException(AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER));
    }
}
