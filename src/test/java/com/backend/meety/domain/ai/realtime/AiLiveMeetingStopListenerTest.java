package com.backend.meety.domain.ai.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import com.backend.meety.domain.recording.owner.RecordingCommand;
import com.backend.meety.domain.recording.owner.RecordingCommandDispatch;
import com.backend.meety.domain.recording.owner.RecordingOwnerService;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveProperties;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.time.Duration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class AiLiveMeetingStopListenerTest {

    private final RecordingOwnerService ownerService = mock(RecordingOwnerService.class);
    private final List<Object> publishedEvents = new ArrayList<>();
    private final List<Runnable> queuedRunners = new ArrayList<>();
    private final TranscriptSaveDispatcher dispatcher = new TranscriptSaveDispatcher(queuedRunners::add,
            new TranscriptSaveProperties(500, 5000, Duration.ofMillis(1), Duration.ZERO),
            mock(TranscriptService.class));
    private final AiLiveMeetingStopListener listener =
            new AiLiveMeetingStopListener(ownerService, publishedEvents::add, dispatcher);

    @Test
    @DisplayName("녹음 종료가 커밋되면 Owner 서버로 STOP 명령을 보낸다")
    void dispatchesStopAfterRecordingCompletedCommit() {
        when(ownerService.dispatch(88L, RecordingCommand.STOP)).thenReturn(RecordingCommandDispatch.SENT);

        listener.stopAiLiveMeeting(new RecordingCompletedEvent(42L, 88L));

        verify(ownerService).dispatch(88L, RecordingCommand.STOP);
        Assertions.assertThat(publishedEvents).isEmpty();
    }

    @Test
    @DisplayName("Owner가 없으면 전사 저장 큐를 거쳐 전사 확정 이벤트를 발행한다")
    void publishesTranscriptFinalizedThroughSaveQueueWhenOwnerAbsent() {
        // given
        when(ownerService.dispatch(88L, RecordingCommand.STOP)).thenReturn(RecordingCommandDispatch.NO_OWNER);

        // when
        listener.stopAiLiveMeeting(new RecordingCompletedEvent(42L, 88L));

        // then
        Assertions.assertThat(publishedEvents).isEmpty();
        Assertions.assertThat(queuedRunners).hasSize(1);
        queuedRunners.forEach(Runnable::run);
        Assertions.assertThat(publishedEvents)
                .containsExactly(new MeetingTranscriptFinalizedEvent(42L, 88L));
    }

    @Test
    @DisplayName("명령 전달에 실패하면 전사 확정 이벤트를 발행하지 않는다")
    void doesNotPublishWhenDispatchFails() {
        when(ownerService.dispatch(88L, RecordingCommand.STOP)).thenReturn(RecordingCommandDispatch.FAILED);

        listener.stopAiLiveMeeting(new RecordingCompletedEvent(42L, 88L));

        Assertions.assertThat(publishedEvents).isEmpty();
    }

    @Test
    void stopRunsAfterCommitSoRollbackDoesNotSendSessionStop() throws Exception {
        Method method = AiLiveMeetingStopListener.class
                .getMethod("stopAiLiveMeeting", RecordingCompletedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        Assertions.assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
