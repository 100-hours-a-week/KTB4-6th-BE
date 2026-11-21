package com.backend.meety.domain.ai.messaging;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRequestPublishSchedulerTest {

    private final AiRequestPublishService publishService = mock(AiRequestPublishService.class);
    private final AiRequestPublisher publisher = mock(AiRequestPublisher.class);
    private final AiRequestPublishScheduler scheduler = new AiRequestPublishScheduler(
            publishService, publisher, new AiRequestPublishProperties(20, Duration.ofSeconds(5)));
    private final AiRequestOutboxRecord outboxRecord =
            new AiRequestOutboxRecord(101L, "token-1", "summary", "{}");

    @Test
    @DisplayName("Confirm을 받지 못하면 PUBLISHING을 유지하도록 발행 결과를 반영하지 않는다")
    void keepsPublishingWhenUnconfirmed() {
        when(publishService.findPublishableIds(20)).thenReturn(List.of(101L));
        when(publishService.claim(101L)).thenReturn(Optional.of(outboxRecord));
        when(publisher.publish(outboxRecord)).thenReturn(AiRequestPublishResult.UNCONFIRMED);

        scheduler.publishPending();

        verify(publishService, never()).markPublished(anyLong(), anyString());
        verify(publishService, never()).revertToUnpublished(anyLong(), anyString());
    }

    @Test
    @DisplayName("선점에 실패한 요청은 발행하지 않는다")
    void skipsUnclaimedRequest() {
        when(publishService.findPublishableIds(20)).thenReturn(List.of(101L));
        when(publishService.claim(101L)).thenReturn(Optional.empty());

        scheduler.publishPending();

        verify(publisher, never()).publish(outboxRecord);
    }

    @Test
    @DisplayName("한 요청 처리 중 예외가 나도 다음 요청을 계속 처리한다")
    void continuesAfterFailure() {
        AiRequestOutboxRecord next = new AiRequestOutboxRecord(102L, "token-2", "summary", "{}");
        when(publishService.findPublishableIds(20)).thenReturn(List.of(101L, 102L));
        when(publishService.claim(101L)).thenThrow(new IllegalStateException("db down"));
        when(publishService.claim(102L)).thenReturn(Optional.of(next));
        when(publisher.publish(next)).thenReturn(AiRequestPublishResult.CONFIRMED);

        scheduler.publishPending();

        verify(publishService).markPublished(102L, "token-2");
    }
}
