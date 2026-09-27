package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.recording.event.RecordingPausedEvent;
import com.backend.meety.domain.recording.event.RecordingResumedEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class AiLiveMeetingPauseResumeListenerTest {

    private final AiLiveMeetingConnectionService connectionService = mock(AiLiveMeetingConnectionService.class);
    private final AiLiveMeetingPauseResumeListener listener = new AiLiveMeetingPauseResumeListener(connectionService);

    @Test
    @DisplayName("녹음 일시정지 커밋 이후 AI pause를 요청한다")
    void pauseAiLiveMeetingAfterRecordingPausedCommit() {
        // 테스트 목적:
        // RecordingPausedEvent가 처리되면 RecordingService 트랜잭션 커밋 이후
        // 기존 AI connection에 session.pause 요청이 위임되는지 검증한다.

        // given
        RecordingPausedEvent event = new RecordingPausedEvent(42L, 88L);

        // when
        listener.pauseAiLiveMeeting(event);

        // then
        verify(connectionService).pause(88L);
    }

    @Test
    @DisplayName("녹음 재개 커밋 이후 AI resume을 요청한다")
    void resumeAiLiveMeetingAfterRecordingResumedCommit() {
        // 테스트 목적:
        // RecordingResumedEvent가 처리되면 RecordingService 트랜잭션 커밋 이후
        // 기존 AI connection에 session.resume 요청이 위임되는지 검증한다.

        // given
        RecordingResumedEvent event = new RecordingResumedEvent(42L, 88L);

        // when
        listener.resumeAiLiveMeeting(event);

        // then
        verify(connectionService).resume(88L);
    }

    @Test
    @DisplayName("AI pause/resume listener는 AFTER_COMMIT 단계에서 실행된다")
    void pauseResumeRunsAfterCommit() throws Exception {
        // 테스트 목적:
        // DB 녹음 상태 변경 트랜잭션이 커밋된 뒤에만
        // AI pause/resume control message 연동이 실행되도록 보장한다.

        // given
        Method pauseMethod = AiLiveMeetingPauseResumeListener.class
                .getMethod("pauseAiLiveMeeting", RecordingPausedEvent.class);
        Method resumeMethod = AiLiveMeetingPauseResumeListener.class
                .getMethod("resumeAiLiveMeeting", RecordingResumedEvent.class);

        // when
        TransactionalEventListener pauseAnnotation = pauseMethod.getAnnotation(TransactionalEventListener.class);
        TransactionalEventListener resumeAnnotation = resumeMethod.getAnnotation(TransactionalEventListener.class);

        // then
        assertThat(pauseAnnotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(resumeAnnotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
