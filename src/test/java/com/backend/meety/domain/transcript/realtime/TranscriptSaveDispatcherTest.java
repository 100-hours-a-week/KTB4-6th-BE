package com.backend.meety.domain.transcript.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentPayload;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.CannotCreateTransactionException;

class TranscriptSaveDispatcherTest {

    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private ThreadPoolTaskExecutor workers;

    @BeforeEach
    void setUp() {
        workers = new ThreadPoolTaskExecutor();
        workers.setCorePoolSize(2);
        workers.setMaxPoolSize(2);
        workers.setQueueCapacity(16);
        workers.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        workers.shutdown();
        workers.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("저장이 막혀 있어도 호출 스레드는 바로 반환하고, 같은 녹음의 저장과 확정은 제출 순서대로 실행한다")
    void returnsImmediatelyAndKeepsOrderWithinRecording() {
        // 테스트 목적:
        // DB 저장이 막혀도 AI 수신 스레드가 기다리지 않고,
        // 확정 작업이 앞서 받은 전사 저장을 추월하지 않는지 검증한다.

        // given
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            AiTranscriptSegmentMessage message = call.getArgument(0);
            order.add("save-" + message.payload().sequenceNumber());
            if (message.payload().sequenceNumber() == 1L) {
                entered.countDown();
                await(release);
            }
            return null;
        }).when(transcriptService).saveFinalSegment(any());
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(1));

        // when
        assertThat(dispatcher.submitSave(message(88L, 1L))).isTrue();
        await(entered);
        assertThat(dispatcher.submitSave(message(88L, 2L))).isTrue();
        dispatcher.submitFinalize(88L, () -> order.add("finalize"));

        // then
        assertThat(order).containsExactly("save-1");
        assertThat(dispatcher.pendingTaskCount()).isEqualTo(2);
        release.countDown();
        awaitIdle(dispatcher);
        assertThat(order).containsExactly("save-1", "save-2", "finalize");
    }

    @Test
    @DisplayName("한 녹음의 저장이 막혀 있어도 다른 녹음의 저장은 다른 worker에서 진행한다")
    void otherRecordingProceedsWhileOneIsBlocked() {
        // given
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherSaved = new CountDownLatch(1);
        doAnswer(call -> {
            AiTranscriptSegmentMessage message = call.getArgument(0);
            if (message.recordingSessionId() == 88L) {
                entered.countDown();
                await(release);
            } else {
                otherSaved.countDown();
            }
            return null;
        }).when(transcriptService).saveFinalSegment(any());
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(1));

        // when
        dispatcher.submitSave(message(88L, 1L));
        await(entered);
        dispatcher.submitSave(message(99L, 1L));

        // then
        await(otherSaved);
        release.countDown();
        awaitIdle(dispatcher);
    }

    @Test
    @DisplayName("DB 일시 장애는 재시도해 저장하고, 재시도 동안 같은 녹음의 다음 저장은 기다린다")
    void retriesTransientFailureBeforeNextTask() {
        // given
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            AiTranscriptSegmentMessage message = call.getArgument(0);
            if (message.payload().sequenceNumber() == 1L && attempts.incrementAndGet() < 3) {
                throw new QueryTimeoutException("connection is not available");
            }
            order.add("save-" + message.payload().sequenceNumber());
            return null;
        }).when(transcriptService).saveFinalSegment(any());
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(3));

        // when
        dispatcher.submitSave(message(88L, 1L));
        dispatcher.submitSave(message(88L, 2L));

        // then
        awaitIdle(dispatcher);
        assertThat(order).containsExactly("save-1", "save-2");
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("재시도 한도를 넘기면 포기하고 같은 녹음의 다음 작업을 진행한다")
    void givesUpAfterRetryMaxDuration() {
        // given
        doThrow(new QueryTimeoutException("connection is not available"))
                .when(transcriptService).saveFinalSegment(message(88L, 1L));
        doNothing().when(transcriptService).saveFinalSegment(message(88L, 2L));
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofMillis(30));

        // when
        dispatcher.submitSave(message(88L, 1L));
        dispatcher.submitSave(message(88L, 2L));

        // then
        awaitIdle(dispatcher);
        verify(transcriptService, atLeast(2)).saveFinalSegment(message(88L, 1L));
        verify(transcriptService).saveFinalSegment(message(88L, 2L));
    }

    @Test
    @DisplayName("비즈니스 예외는 재시도하지 않는다")
    void doesNotRetryBusinessException() {
        // given
        doThrow(new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND))
                .when(transcriptService).saveFinalSegment(any());
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(3));

        // when
        dispatcher.submitSave(message(88L, 1L));

        // then
        awaitIdle(dispatcher);
        verify(transcriptService, times(1)).saveFinalSegment(any());
    }

    @Test
    @DisplayName("DB·트랜잭션 예외가 아닌 코드 오류는 재시도하지 않아 같은 녹음의 다음 저장을 막지 않는다")
    void doesNotRetryProgrammingError() {
        // 테스트 목적:
        // 다시 해도 똑같이 실패하는 코드 오류를 재시도 한도 동안 붙잡고 있어
        // 같은 녹음의 다음 전사 저장이 밀리지 않는지 검증한다.

        // given
        doThrow(new IllegalStateException("bug")).when(transcriptService).saveFinalSegment(message(88L, 1L));
        doNothing().when(transcriptService).saveFinalSegment(message(88L, 2L));
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(3));

        // when
        dispatcher.submitSave(message(88L, 1L));
        dispatcher.submitSave(message(88L, 2L));

        // then
        awaitIdle(dispatcher);
        verify(transcriptService, times(1)).saveFinalSegment(message(88L, 1L));
        verify(transcriptService).saveFinalSegment(message(88L, 2L));
    }

    @Test
    @DisplayName("확정 작업도 커넥션을 얻지 못하면 재시도해 남은 저장 뒤에 실행된다")
    void retriesFinalizeAfterTransactionFailure() {
        // 테스트 목적:
        // DB 전환 중 요약·화자 분리 등록이 커넥션 획득 실패로 끝나도
        // 재시도로 다시 실행되어 요약 등록이 누락되지 않는지 검증한다.

        // given
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            order.add("save");
            return null;
        }).when(transcriptService).saveFinalSegment(any());
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(3));

        // when
        dispatcher.submitSave(message(88L, 1L));
        dispatcher.submitFinalize(88L, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new CannotCreateTransactionException("Connection is not available");
            }
            order.add("finalize");
        });

        // then
        awaitIdle(dispatcher);
        assertThat(order).containsExactly("save", "finalize");
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("녹음별·전체 상한을 넘은 저장은 거절하고 기존 작업은 그대로 진행한다")
    void rejectsOverCapacity() {
        // given
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            await(release);
            return null;
        }).when(transcriptService).saveFinalSegment(message(88L, 1L));
        TranscriptSaveDispatcher dispatcher = dispatcher(1, 2, Duration.ofSeconds(1));

        // when
        dispatcher.submitSave(message(88L, 1L));
        await(entered);
        boolean queued = dispatcher.submitSave(message(88L, 2L));
        boolean overRecording = dispatcher.submitSave(message(88L, 3L));
        boolean otherQueued = dispatcher.submitSave(message(99L, 1L));
        boolean overTotal = dispatcher.submitSave(message(77L, 1L));

        // then
        assertThat(queued).isTrue();
        assertThat(overRecording).isFalse();
        assertThat(otherQueued).isTrue();
        assertThat(overTotal).isFalse();
        release.countDown();
        awaitIdle(dispatcher);
        verify(transcriptService).saveFinalSegment(message(88L, 2L));
        verify(transcriptService, times(0)).saveFinalSegment(message(88L, 3L));
    }

    @Test
    @DisplayName("Executor가 거절하면 queue와 pending을 되돌리고, 확정 작업은 바로 실행한다")
    void executorRejectionRollsBackAndRunsFinalizeInline() {
        // given
        TranscriptSaveDispatcher dispatcher = new TranscriptSaveDispatcher(
                task -> {
                    throw new RejectedExecutionException("saturated");
                },
                properties(500, 5000, Duration.ofSeconds(1)), transcriptService);
        AtomicInteger finalized = new AtomicInteger();

        // when
        boolean saved = dispatcher.submitSave(message(88L, 1L));
        dispatcher.submitFinalize(88L, finalized::incrementAndGet);

        // then
        assertThat(saved).isFalse();
        assertThat(finalized).hasValue(1);
        assertThat(dispatcher.pendingTaskCount()).isZero();
        assertThat(dispatcher.recordingQueueCount()).isZero();
    }

    @Test
    @DisplayName("종료 중에는 저장을 거절하고 확정 작업은 바로 실행한다")
    void closedRejectsSaveAndRunsFinalizeInline() {
        // given
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(1));
        dispatcher.stopAccepting();
        AtomicInteger finalized = new AtomicInteger();

        // when
        boolean saved = dispatcher.submitSave(message(88L, 1L));
        dispatcher.submitFinalize(88L, finalized::incrementAndGet);

        // then
        assertThat(saved).isFalse();
        assertThat(finalized).hasValue(1);
        verify(transcriptService, times(0)).saveFinalSegment(any());
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 같은 녹음의 다음 작업과 queue 정리는 진행한다")
    void taskFailureDoesNotWedgeQueue() {
        // given
        TranscriptSaveDispatcher dispatcher = dispatcher(500, 5000, Duration.ofSeconds(1));
        AtomicInteger finalized = new AtomicInteger();

        // when
        dispatcher.submitFinalize(88L, () -> {
            throw new IllegalStateException("listener failed");
        });
        dispatcher.submitFinalize(88L, finalized::incrementAndGet);

        // then
        awaitIdle(dispatcher);
        assertThat(finalized).hasValue(1);
    }

    private TranscriptSaveDispatcher dispatcher(int perRecording, int total, Duration retryMaxDuration) {
        return new TranscriptSaveDispatcher(workers, properties(perRecording, total, retryMaxDuration),
                transcriptService);
    }

    private static TranscriptSaveProperties properties(int perRecording, int total, Duration retryMaxDuration) {
        return new TranscriptSaveProperties(perRecording, total, Duration.ofMillis(5), retryMaxDuration);
    }

    private static AiTranscriptSegmentMessage message(Long recordingSessionId, Long sequenceNumber) {
        return new AiTranscriptSegmentMessage(AiTranscriptSegmentMessage.TYPE, 42L, recordingSessionId,
                new AiTranscriptSegmentPayload(sequenceNumber, "text", 0L, 1000L, LocalDateTime.of(2026, 10, 21, 0, 0)));
    }



    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitIdle(TranscriptSaveDispatcher dispatcher) {
        BooleanSupplier idle = () -> dispatcher.recordingQueueCount() == 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!idle.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(idle.getAsBoolean()).isTrue();
        assertThat(dispatcher.pendingTaskCount()).isZero();
    }
}
