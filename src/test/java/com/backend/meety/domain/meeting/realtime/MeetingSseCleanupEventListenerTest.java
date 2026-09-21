package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseCleanupEventListenerTest {

    @Test
    void leftEventClosesOnlyParticipantEmitter() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(registry);

        listener.closeParticipantSse(new MeetingParticipantLeftEvent(100L, 10L));

        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isFalse();
        assertThat(registry.find(100L, 10L)).isEmpty();
        assertThat(registry.find(100L, 20L)).contains(user20);
    }

    @Test
    void completedEventClosesAllMeetingEmitters() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(registry);

        listener.closeMeetingSse(new MeetingCompletedEvent(100L));

        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
    }

    @Test
    void cleanupListenersRunAfterCommit() throws Exception {
        Method left = MeetingSseCleanupEventListener.class
                .getMethod("closeParticipantSse", MeetingParticipantLeftEvent.class);
        Method completed = MeetingSseCleanupEventListener.class
                .getMethod("closeMeetingSse", MeetingCompletedEvent.class);

        assertThat(left.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(completed.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    private static class TestSseEmitter extends SseEmitter {

        private boolean completed;

        @Override
        public void complete() {
            completed = true;
        }
    }
}
