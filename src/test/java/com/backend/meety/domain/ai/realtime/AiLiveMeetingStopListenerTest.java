package com.backend.meety.domain.ai.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class AiLiveMeetingStopListenerTest {

    private final AiLiveMeetingConnectionService connectionService = mock(AiLiveMeetingConnectionService.class);
    private final AiLiveMeetingConnectionRegistry registry = new AiLiveMeetingConnectionRegistry();
    private final List<Object> publishedEvents = new ArrayList<>();
    private final AiLiveMeetingStopListener listener =
            new AiLiveMeetingStopListener(connectionService, registry, publishedEvents::add);

    @Test
    void stopAiLiveMeetingAfterRecordingCompletedCommit() {
        listener.stopAiLiveMeeting(new RecordingCompletedEvent(42L, 88L));

        verify(connectionService).stop(88L);
    }

    @Test
    void publishesTranscriptFinalizedWhenConnectionAbsent() {
        listener.stopAiLiveMeeting(new RecordingCompletedEvent(42L, 88L));

        Assertions.assertThat(publishedEvents)
                .containsExactly(new MeetingTranscriptFinalizedEvent(42L, 88L));
    }

    @Test
    void doesNotPublishWhenConnectionExists() {
        registry.reserve(new AiLiveMeetingConnection(88L, 42L, AudioFormat.WEBM_OPUS, "start-test"));

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
