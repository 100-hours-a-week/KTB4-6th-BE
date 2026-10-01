package com.backend.meety.domain.ai.realtime;

public record AiDecoderResetMessage(
        String type,
        String requestId,
        Payload payload
) {

    private static final String TYPE = "decoder.reset";

    public static AiDecoderResetMessage create(AiLiveMeetingConnection connection) {
        return new AiDecoderResetMessage(
                TYPE,
                connection.decoderResetRequestId(),
                new Payload(connection.audioFormat().value())
        );
    }

    public record Payload(String audioFormat) {
    }
}
