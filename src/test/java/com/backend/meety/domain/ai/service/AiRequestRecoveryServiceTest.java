package com.backend.meety.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.AiRequestRecoveryProperties;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.event.StaleAiRequestFailedEvent;
import com.backend.meety.domain.ai.repository.AiRequestRecoveryCandidate;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

class AiRequestRecoveryServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-16T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime STALE_BEFORE = LocalDateTime.of(2026, 10, 16, 10, 50);
    private static final AiRequestRecoveryProperties PROPERTIES =
            new AiRequestRecoveryProperties(Duration.ofMinutes(70), 100);

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AiRequestRecoveryService service = new AiRequestRecoveryService(
            aiRequests, PROPERTIES, eventPublisher, CLOCK);

    @Test
    @DisplayName("stale PROCESSING 요청의 조건부 복구 성공을 집계한다")
    void recoversStaleProcessing() {
        // 테스트 목적:
        // 설정된 timeout보다 오래된 요청이 retry 한도 미만이면
        // ACCEPTED 복구 성공 1건으로 집계되는지 검증한다.

        // given
        AiRequestRecoveryCandidate candidate = new AiRequestRecoveryCandidate(10L, AiRequestType.SUMMARY);
        when(aiRequests.findStaleProcessingCandidates(
                List.of(AiRequestType.SUMMARY, AiRequestType.DIARIZATION, AiRequestType.METRIC),
                STALE_BEFORE, Limit.of(100))).thenReturn(List.of(candidate));
        when(aiRequests.recoverStaleProcessing(10L, STALE_BEFORE, 2L)).thenReturn(1);

        // when
        AiRequestRecoveryResult result = service.recoverStaleProcessing();

        // then
        assertThat(result).isEqualTo(new AiRequestRecoveryResult(1, 1, 0, 0));
        verify(aiRequests, never()).failStaleProcessing(any(), any(), eq(2L), eq(3L));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("재시도 한도에 도달한 stale PROCESSING 요청을 FAILED로 집계한다")
    void failsStaleProcessingAtRetryLimit() {
        // 테스트 목적:
        // 재시도 가능한 조건부 UPDATE가 실패하고 마지막 재시도 조건을 만족하면
        // FAILED 전이와 후처리 이벤트가 정확히 한 번 발생하는지 검증한다.

        // given
        AiRequestRecoveryCandidate candidate = new AiRequestRecoveryCandidate(20L, AiRequestType.SUMMARY);
        when(aiRequests.findStaleProcessingCandidates(any(), eq(STALE_BEFORE), eq(Limit.of(100))))
                .thenReturn(List.of(candidate));
        when(aiRequests.recoverStaleProcessing(20L, STALE_BEFORE, 2L)).thenReturn(0);
        when(aiRequests.failStaleProcessing(20L, STALE_BEFORE, 2L, 3L)).thenReturn(1);

        // when
        AiRequestRecoveryResult result = service.recoverStaleProcessing();

        // then
        assertThat(result).isEqualTo(new AiRequestRecoveryResult(1, 0, 1, 0));
        verify(eventPublisher).publishEvent(new StaleAiRequestFailedEvent(20L, AiRequestType.SUMMARY));
    }

    @Test
    @DisplayName("다른 인스턴스가 먼저 복구한 stale 요청은 정상 miss로 집계한다")
    void countsConcurrentRecoveryMiss() {
        // 테스트 목적:
        // 후보 조회 이후 다른 인스턴스가 먼저 상태를 변경해 두 조건부 UPDATE가 모두 0이면
        // 실패 예외가 아닌 정상적인 복구 경쟁 miss로 처리되는지 검증한다.

        // given
        AiRequestRecoveryCandidate candidate = new AiRequestRecoveryCandidate(30L, AiRequestType.METRIC);
        when(aiRequests.findStaleProcessingCandidates(any(), eq(STALE_BEFORE), eq(Limit.of(100))))
                .thenReturn(List.of(candidate));

        // when
        AiRequestRecoveryResult result = service.recoverStaleProcessing();

        // then
        assertThat(result).isEqualTo(new AiRequestRecoveryResult(1, 0, 0, 1));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Summary, Diarization, Metric stale 요청을 공통 복구 흐름으로 처리한다")
    void recoversAllPollingRequestTypes() {
        // 테스트 목적:
        // 세 polling 도메인의 stale 요청이 각 도메인의 기존 최대 재시도 정책을 사용하면서
        // 동일한 조건부 복구 흐름으로 처리되는지 검증한다.

        // given
        List<AiRequestRecoveryCandidate> candidates = List.of(
                new AiRequestRecoveryCandidate(41L, AiRequestType.SUMMARY),
                new AiRequestRecoveryCandidate(42L, AiRequestType.DIARIZATION),
                new AiRequestRecoveryCandidate(43L, AiRequestType.METRIC));
        when(aiRequests.findStaleProcessingCandidates(any(), eq(STALE_BEFORE), eq(Limit.of(100))))
                .thenReturn(candidates);
        when(aiRequests.recoverStaleProcessing(any(), eq(STALE_BEFORE), eq(2L))).thenReturn(1);

        // when
        AiRequestRecoveryResult result = service.recoverStaleProcessing();

        // then
        assertThat(result).isEqualTo(new AiRequestRecoveryResult(3, 3, 0, 0));
        verify(aiRequests).recoverStaleProcessing(41L, STALE_BEFORE, 2L);
        verify(aiRequests).recoverStaleProcessing(42L, STALE_BEFORE, 2L);
        verify(aiRequests).recoverStaleProcessing(43L, STALE_BEFORE, 2L);
    }
}
