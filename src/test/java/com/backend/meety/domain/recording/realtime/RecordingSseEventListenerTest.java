package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import com.backend.meety.domain.recording.event.RecordingPausedEvent;
import com.backend.meety.domain.recording.event.RecordingResumedEvent;
import com.backend.meety.domain.recording.event.RecordingStartedEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RecordingSseEventListenerTest {

    @Test
    void startedEventBroadcastsToAllMeetingEmittersAndKeepsConnections() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter starter = new TestSseEmitter();
        TestSseEmitter participant = new TestSseEmitter();
        TestSseEmitter otherMeeting = new TestSseEmitter();
        registry.register(100L, 10L, starter);
        registry.register(100L, 20L, participant);
        registry.register(200L, 30L, otherMeeting);
        RecordingSseEventListener listener = new RecordingSseEventListener(registry);

        listener.broadcastStarted(new RecordingStartedEvent(100L, 700L));

        RecordingStatusSseEvent expected = new RecordingStatusSseEvent("RECORDING_STARTED", 100L, 700L);
        assertReceived(starter, "RECORDING_STARTED", expected);
        assertReceived(participant, "RECORDING_STARTED", expected);
        assertThat(otherMeeting.sentData).isNull();
        assertThat(starter.completed).isFalse();
        assertThat(participant.completed).isFalse();
        assertThat(registry.find(100L, 10L)).contains(starter);
        assertThat(registry.find(100L, 20L)).contains(participant);
        assertThat(registry.find(200L, 30L)).contains(otherMeeting);
    }

    @Test
    void pausedResumedAndCompletedUseExpectedEventNames() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter paused = new TestSseEmitter();
        TestSseEmitter resumed = new TestSseEmitter();
        TestSseEmitter completed = new TestSseEmitter();
        RecordingSseEventListener listener = new RecordingSseEventListener(registry);

        registry.register(100L, 10L, paused);
        listener.broadcastPaused(new RecordingPausedEvent(100L, 700L));
        assertReceived(paused, "RECORDING_PAUSED", new RecordingStatusSseEvent("RECORDING_PAUSED", 100L, 700L));

        registry.register(101L, 10L, resumed);
        listener.broadcastResumed(new RecordingResumedEvent(101L, 701L));
        assertReceived(resumed, "RECORDING_RESUMED", new RecordingStatusSseEvent("RECORDING_RESUMED", 101L, 701L));

        registry.register(102L, 10L, completed);
        listener.broadcastCompleted(new RecordingCompletedEvent(102L, 702L));
        assertReceived(completed, "RECORDING_COMPLETED",
                new RecordingStatusSseEvent("RECORDING_COMPLETED", 102L, 702L));
    }

    @Test
    void failedEmitterDoesNotBlockOtherEmitters() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        user20.sendFailure = new IOException("send failed");
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);
        RecordingSseEventListener listener = new RecordingSseEventListener(registry);

        listener.broadcastPaused(new RecordingPausedEvent(100L, 700L));

        RecordingStatusSseEvent expected = new RecordingStatusSseEvent("RECORDING_PAUSED", 100L, 700L);
        assertReceived(user10, "RECORDING_PAUSED", expected);
        assertThat(user20.sendAttempted).isTrue();
        assertThat(user20.completed).isTrue();
        assertReceived(user30, "RECORDING_PAUSED", expected);
        assertThat(registry.find(100L, 10L)).contains(user10);
        assertThat(registry.find(100L, 20L)).isEmpty();
        assertThat(registry.find(100L, 30L)).contains(user30);
    }

    @Test
    void recordingSseListenersRunAfterCommit() throws Exception {
        Method started = RecordingSseEventListener.class
                .getMethod("broadcastStarted", RecordingStartedEvent.class);
        Method paused = RecordingSseEventListener.class
                .getMethod("broadcastPaused", RecordingPausedEvent.class);
        Method resumed = RecordingSseEventListener.class
                .getMethod("broadcastResumed", RecordingResumedEvent.class);
        Method completed = RecordingSseEventListener.class
                .getMethod("broadcastCompleted", RecordingCompletedEvent.class);

        assertThat(started.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(paused.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(resumed.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(completed.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    private void assertReceived(TestSseEmitter emitter, String eventName, RecordingStatusSseEvent payload) {
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(payload)
                .anyMatch(data -> data instanceof String value && value.startsWith("event:" + eventName + "\n"));
    }

    private static class TestSseEmitter extends SseEmitter {

        private boolean completed;
        private boolean sendAttempted;
        private IOException sendFailure;
        private Set<ResponseBodyEmitter.DataWithMediaType> sentData;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempted = true;
            if (sendFailure != null) {
                throw sendFailure;
            }
            sentData = builder.build();
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
