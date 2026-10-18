package com.backend.meety.global.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InternalApiHeader {

    public static final String API_KEY = "X-Internal-Api-Key";
    public static final String AI_REQUEST_ID = "X-Ai-Request-Id";
}
