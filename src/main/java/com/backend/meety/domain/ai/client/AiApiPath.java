package com.backend.meety.domain.ai.client;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AiApiPath {

    public static final String SUMMARY = "/v1/summary";
    public static final String DIARIZATION = "/v1/diarization";
    public static final String CHAT = "/v1/chat";
}
