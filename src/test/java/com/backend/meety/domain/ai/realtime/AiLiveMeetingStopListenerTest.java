package com.backend.meety.domain.ai.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import java.lang.reflect.Method;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class AiLiveMeetingStopListenerTest {

    private final AiLiveMeetingConnectionService connectionService = mock(AiLiveMeetingConnectionService.class);
    private final AiLiveMeetingStopListener listener = new AiLiveMeetingStopListener(connectionService);

    @Test
    void stopAiLiveMeetingAfterRecordingCompletedCommit() {
        listener.stopAiLiveMeeting(new RecordingCompletedEvent(42L, 88L));

        verify(connectionService).stop(88L);
    }

    @Test
    void stopRunsAfterCommitSoRollbackDoesNotSendSessionStop() throws Exception {
        Method method = AiLiveMeetingStopListener.class
                .getMethod("stopAiLiveMeeting", RecordingCompletedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        Assertions.assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
