package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.client.AiRequestInput;
import com.backend.meety.domain.ai.entity.AiRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AiRequestPublishPreparer {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void prepare(AiRequest aiRequest, AiRequestInput input) {
        String processingToken = UUID.randomUUID().toString();
        AiRequestMessage message = AiRequestMessage.create(aiRequest.getId(), processingToken, input);
        String routingKey = AiRabbitTopology.requestRoutingKey(input.requestType())
                .orElseThrow(() -> new IllegalArgumentException("Not an MQ request type: " + input.requestType()));
        aiRequest.preparePublish(processingToken, objectMapper.writeValueAsString(message), routingKey,
                LocalDateTime.now(clock));
    }
}
