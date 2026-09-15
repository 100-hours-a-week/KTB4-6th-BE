package com.backend.meety.domain.auth.service;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.dto.OAuthUserInfo;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.service.UserAccountService;
import com.backend.meety.global.security.JwtTokenProvider;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final Map<String, OAuthProviderClient> oAuthProviderClients;
    private final UserAccountService userAccountService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResponse login(String provider, String authorizationCode) {
        OAuthProviderClient client = findClient(provider);
        OAuthUserInfo userInfo = client.fetchUserInfo(authorizationCode);
        User user = findOrCreateUser(userInfo);
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = refreshTokenService.issue(user);
        return new LoginResponse(user.getId(), accessToken, refreshToken);
    }

    private OAuthProviderClient findClient(String provider) {
        OAuthProviderClient client = oAuthProviderClients.get(provider);
        if (client == null) {
            throw new AuthException(AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
        return client;
    }

    private User findOrCreateUser(OAuthUserInfo userInfo) {
        try {
            return userAccountService.findOrCreate(userInfo.provider(), userInfo.providerUserId());
        } catch (DataIntegrityViolationException e) {
            return userAccountService.getByProviderAndProviderUserId(userInfo.provider(), userInfo.providerUserId());
        }
    }
}
