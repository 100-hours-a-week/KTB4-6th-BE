package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatProcessingService {

    private final AiChatbotMessageRepository chatbotMessageRepository;
    private final ChatAiRequestFactory chatAiRequestFactory;

    @Transactional
    public Optional<ChatAiRequest> startProcessing(Long aiRequestId) {
        return chatbotMessageRepository.findWithRequestByAiRequestId(aiRequestId)
                .filter(message -> message.getAiRequest().isAccepted())
                .map(this::markProcessing);
    }

    private ChatAiRequest markProcessing(AiChatbotMessage message) {
        message.getAiRequest().markProcessing();
        return chatAiRequestFactory.create(message);
    }
}
