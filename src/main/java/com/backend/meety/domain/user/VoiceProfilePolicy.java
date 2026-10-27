package com.backend.meety.domain.user;

import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VoiceProfilePolicy {

    private static final String STORAGE_KEY_PREFIX = "voice-profiles/";

    public static String storageKey(Long userId) {
        return STORAGE_KEY_PREFIX + userId + "." + AudioFilePolicy.EXTENSION;
    }
}
