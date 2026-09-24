package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.event.MeetingDeletedEvent;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
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
    void deletedEventSendsDeletedEventAndClosesAllMeetingEmitters() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(registry);

        listener.sendDeletedEventAndCloseMeetingSse(new MeetingDeletedEvent(100L));

        assertThat(user10.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseDeletedEvent.deleted(100L))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:MEETING_DELETED\n"));
        assertThat(user20.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseDeletedEvent.deleted(100L));
        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
    }

    @Test
    void deletedEventWithNoEmitterIsNoop() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(registry);

        listener.sendDeletedEventAndCloseMeetingSse(new MeetingDeletedEvent(100L));

        assertThat(registry.countAll()).isZero();
    }

    @Test
    void cleanupListenersRunAfterCommit() throws Exception {
        Method left = MeetingSseCleanupEventListener.class
                .getMethod("closeParticipantSse", MeetingParticipantLeftEvent.class);
        Method completed = MeetingSseCleanupEventListener.class
                .getMethod("closeMeetingSse", MeetingCompletedEvent.class);
        Method deleted = MeetingSseCleanupEventListener.class
                .getMethod("sendDeletedEventAndCloseMeetingSse", MeetingDeletedEvent.class);

        assertThat(left.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(completed.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(deleted.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(completed.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    private static class TestSseEmitter extends SseEmitter {

        private boolean completed;
        private Set<ResponseBodyEmitter.DataWithMediaType> sentData;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sentData = builder.build();
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
