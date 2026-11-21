package com.backend.meety.domain.ai.messaging;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AiResponseMessageParser {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public Optional<AiResponseMessage> parse(byte[] body) {
        AiResponseMessage message;
        try {
            message = objectMapper.readValue(body, AiResponseMessage.class);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        return isValid(message) ? Optional.of(message) : Optional.empty();
    }

    private boolean isValid(AiResponseMessage message) {
        if (message == null
                || !Integer.valueOf(SUPPORTED_SCHEMA_VERSION).equals(message.schemaVersion())
                || message.aiRequestId() == null
                || message.processingToken() == null || message.processingToken().isBlank()
                || message.requestType() == null
                || AiRabbitTopology.requestRoutingKey(message.requestType()).isEmpty()
                || message.eventType() == null) {
            return false;
        }
        return switch (message.eventType()) {
            case SUCCEEDED -> message.result() != null && !message.result().isNull();
            case FAILED -> message.error() != null
                    && message.error().code() != null && !message.error().code().isBlank();
        };
    }
}
