package com.backend.meety.domain.ai.realtime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChatSseEventName {

    public static final String CHAT_REQUESTED = "CHAT_REQUESTED";
    public static final String CHAT_COMPLETED = "CHAT_COMPLETED";
    public static final String CHAT_FAILED = "CHAT_FAILED";
}
