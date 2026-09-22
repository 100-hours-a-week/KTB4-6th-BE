package com.backend.meety.domain.ai.realtime;

public record AiAudioMetaMessage(
        String type,
        AiAudioMetaPayload payload
) {

    private static final String TYPE = "audio.meta";

    public static AiAudioMetaMessage of(long sequence) {
        return new AiAudioMetaMessage(TYPE, new AiAudioMetaPayload(sequence));
    }
}
