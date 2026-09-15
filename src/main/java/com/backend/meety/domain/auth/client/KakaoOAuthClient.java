package com.backend.meety.domain.auth.client;

import com.backend.meety.domain.auth.dto.OAuthUserInfo;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component(KakaoOAuthClient.REGISTRATION_ID)
@RequiredArgsConstructor
public class KakaoOAuthClient implements OAuthProviderClient {

    static final String REGISTRATION_ID = "kakao";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService;

    @Override
    public OAuthUserInfo fetchUserInfo(String authorizationCode) {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        OAuth2AccessTokenResponse tokenResponse = exchangeToken(registration, authorizationCode);
        OAuth2User oAuth2User = loadUser(registration, tokenResponse);
        return new OAuthUserInfo(REGISTRATION_ID, oAuth2User.getName());
    }

    private OAuth2AccessTokenResponse exchangeToken(ClientRegistration registration, String authorizationCode) {
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .clientId(registration.getClientId())
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .redirectUri(registration.getRedirectUri())
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success(authorizationCode)
                .redirectUri(registration.getRedirectUri())
                .build();
        OAuth2AuthorizationCodeGrantRequest grantRequest = new OAuth2AuthorizationCodeGrantRequest(
                registration, new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
        try {
            return tokenResponseClient.getTokenResponse(grantRequest);
        } catch (OAuth2AuthorizationException e) {
            log.warn("카카오 토큰 교환에 실패했습니다. errorCode={}", e.getError().getErrorCode());
            throw new AuthException(AuthErrorCode.KAKAO_AUTH_FAILED);
        } catch (RestClientException e) {
            log.error("카카오 토큰 교환 중 통신 오류가 발생했습니다.", e);
            throw new AuthException(AuthErrorCode.AUTH_PROCESSING_FAILED);
        }
    }

    private OAuth2User loadUser(ClientRegistration registration, OAuth2AccessTokenResponse tokenResponse) {
        try {
            return oAuth2UserService.loadUser(new OAuth2UserRequest(registration, tokenResponse.getAccessToken()));
        } catch (OAuth2AuthenticationException e) {
            log.warn("카카오 사용자 정보 조회에 실패했습니다.", e);
            throw new AuthException(AuthErrorCode.KAKAO_AUTH_FAILED);
        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 조회 중 통신 오류가 발생했습니다.", e);
            throw new AuthException(AuthErrorCode.AUTH_PROCESSING_FAILED);
        }
    }
}
