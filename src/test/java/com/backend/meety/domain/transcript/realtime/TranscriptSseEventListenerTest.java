package com.backend.meety.domain.transcript.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TranscriptSseEventListenerTest {

    @Test
    void transcriptCreatedEventBroadcastsToAllMeetingEmitters() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter starter = new TestSseEmitter();
        TestSseEmitter participant = new TestSseEmitter();
        TestSseEmitter otherMeeting = new TestSseEmitter();
        registry.register(100L, 10L, starter);
        registry.register(100L, 20L, participant);
        registry.register(200L, 30L, otherMeeting);
        TranscriptSseEventListener listener = new TranscriptSseEventListener(registry);
        LocalDateTime recognizedAt = LocalDateTime.of(2026, 9, 23, 10, 15, 30);

        listener.broadcastTranscriptCreated(new TranscriptCreatedEvent(
                100L,
                900L,
                31L,
                "final text",
                12000L,
                14500L,
                recognizedAt
        ));

        TranscriptCreatedSseEvent expected = TranscriptCreatedSseEvent.of(
                100L, 900L, 31L, "final text", 12000L, 14500L, recognizedAt);
        assertThat(starter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(expected)
                .anyMatch(data -> data instanceof String value && value.startsWith("event:TRANSCRIPT_CREATED\n"));
        assertThat(participant.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(expected);
        assertThat(otherMeeting.sentData).isNull();
        assertThat(starter.completed).isFalse();
        assertThat(participant.completed).isFalse();
        assertThat(registry.find(100L, 10L)).contains(starter);
        assertThat(registry.find(100L, 20L)).contains(participant);
        assertThat(registry.find(200L, 30L)).contains(otherMeeting);
    }

    @Test
    void transcriptCreatedListenerRunsAfterCommit() throws Exception {
        Method method = TranscriptSseEventListener.class
                .getMethod("broadcastTranscriptCreated", TranscriptCreatedEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
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
