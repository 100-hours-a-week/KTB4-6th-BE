package com.backend.meety.domain.auth.client;

import com.backend.meety.domain.auth.dto.OAuthUserInfo;

public interface OAuthProviderClient {

    OAuthUserInfo fetchUserInfo(String authorizationCode);
}
