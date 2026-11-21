package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.repository.AiRequestRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiRequestPublishService {

    private final AiRequestRepository aiRequestRepository;
    private final Clock clock;

    public List<Long> findPublishableIds(int batchSize) {
        return aiRequestRepository.findPublishableIds(LocalDateTime.now(clock), Limit.of(batchSize));
    }

    @Transactional
    public Optional<AiRequestOutboxRecord> claim(Long aiRequestId) {
        if (aiRequestRepository.claimPublish(aiRequestId, LocalDateTime.now(clock)) == 0) {
            return Optional.empty();
        }
        return aiRequestRepository.findById(aiRequestId).map(AiRequestOutboxRecord::create);
    }

    @Transactional
    public boolean markPublished(Long aiRequestId, String processingToken) {
        return aiRequestRepository.markPublished(aiRequestId, processingToken, LocalDateTime.now(clock)) == 1;
    }

    @Transactional
    public boolean revertToUnpublished(Long aiRequestId, String processingToken) {
        return aiRequestRepository.revertToUnpublished(aiRequestId, processingToken) == 1;
    }
}
