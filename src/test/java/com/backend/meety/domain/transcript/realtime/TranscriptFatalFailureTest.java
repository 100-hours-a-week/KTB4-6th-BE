package com.backend.meety.domain.transcript.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

class TranscriptFatalFailureTest {
    final TranscriptService service = mock(TranscriptService.class);
    final AiTranscriptSegmentMessage message = mock(AiTranscriptSegmentMessage.class);
    final AtomicInteger failed = new AtomicInteger();

    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("retry 불가 DB 실패와 retry 소진은 각각 Fatal callback을 한 번 실행한다")
    void finalFailureIsReported(boolean retryable) {
        // 테스트 목적:
        // 기존 bounded retry 이후에도 저장 실패가 로그만 남기고 유실되지 않는지 검증한다.
        // given
        when(message.recordingSessionId()).thenReturn(317L);
        doThrow(retryable ? new TransientDataAccessResourceException("unavailable")
                : new DataIntegrityViolationException("invalid")).when(service).saveFinalSegment(message);
        var dispatcher = new TranscriptSaveDispatcher(Runnable::run, properties(Duration.ZERO), service);
        // when
        dispatcher.submitSave(message, failed::incrementAndGet);
        // then
        assertThat(failed.get()).isEqualTo(1); verify(service).saveFinalSegment(message);
    }

    @Test @DisplayName("기존 전사 DB retry가 성공하면 Fatal callback은 실행하지 않는다")
    void retrySuccessIsNotFatal() {
        // 테스트 목적:
        // 일시적인 DB 오류의 기존 재시도 정책이 즉시 Fatal로 바뀌지 않았는지 검증한다.
        // given
        when(message.recordingSessionId()).thenReturn(317L);
        doThrow(new TransientDataAccessResourceException("once")).doNothing().when(service).saveFinalSegment(message);
        var dispatcher = new TranscriptSaveDispatcher(Runnable::run, properties(Duration.ofSeconds(1)), service);
        // when
        dispatcher.submitSave(message, failed::incrementAndGet);
        // then
        assertThat(failed.get()).isZero(); verify(service, times(2)).saveFinalSegment(message);
    }

    @Test @DisplayName("전사 저장 큐 제출 거절도 Fatal callback으로 전달한다")
    void rejectedSaveIsFatal() {
        // 테스트 목적:
        // 실제 저장조차 하지 못한 영구 유실을 정상 수락으로 취급하지 않는지 검증한다.
        // given
        when(message.recordingSessionId()).thenReturn(317L);
        var dispatcher = new TranscriptSaveDispatcher(task -> { throw new RejectedExecutionException(); },
                properties(Duration.ZERO), service);
        // when
        boolean accepted = dispatcher.submitSave(message, failed::incrementAndGet);
        // then
        assertThat(accepted).isFalse(); assertThat(failed.get()).isEqualTo(1); verifyNoInteractions(service);
    }

    TranscriptSaveProperties properties(Duration duration) {
        return new TranscriptSaveProperties(10, 100, Duration.ofMillis(1), Duration.ofMillis(2), duration);
    }
}
