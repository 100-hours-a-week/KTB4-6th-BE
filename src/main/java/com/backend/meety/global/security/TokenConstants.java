package com.backend.meety.global.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TokenConstants {

    public static final int REFRESH_TOKEN_BYTE_LENGTH = 32;
    public static final String ROLE_CLAIM = "role";
    public static final String ACCESS_TOKEN_COOKIE = "accessToken";
    public static final String AUTH_FAILURE_ATTRIBUTE = "authFailure";
    public static final String ROLE_PREFIX = "ROLE_";

}
