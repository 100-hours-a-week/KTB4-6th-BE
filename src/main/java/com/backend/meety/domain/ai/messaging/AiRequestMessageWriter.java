package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.client.AiRequestInput;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AiRequestMessageWriter {

    private final AiRequestRepository aiRequestRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void write(AiRequest aiRequest, AiRequestInput input) {
        String processingToken = UUID.randomUUID().toString();
        AiRequestMessage message = AiRequestMessage.create(aiRequest.getId(), processingToken, input);
        String routingKey = AiRabbitTopology.requestRoutingKey(input.requestType())
                .orElseThrow(() -> new IllegalArgumentException("Not an MQ request type: " + input.requestType()));
        aiRequest.preparePublish(processingToken, objectMapper.writeValueAsString(message), routingKey,
                LocalDateTime.now(clock));
        aiRequestRepository.save(aiRequest);
    }
}
