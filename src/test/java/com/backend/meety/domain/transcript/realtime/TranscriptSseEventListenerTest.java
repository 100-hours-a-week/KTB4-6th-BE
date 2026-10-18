package com.backend.meety.domain.transcript.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.realtime.MeetingSseLoopback;
import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TranscriptSseEventListenerTest {

    @Test
    @DisplayName("저장된 전사 이벤트는 해당 회의 참가자에게 기존 payload로 전달한다")
    void transcriptCreatedEventBroadcastsToAllMeetingEmitters() {
        // 테스트 목적:
        // 저장된 전사 이벤트는 해당 회의 참가자에게 기존 payload로 전달한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter starter = new TestSseEmitter();
        TestSseEmitter participant = new TestSseEmitter();
        TestSseEmitter otherMeeting = new TestSseEmitter();
        registry.register(100L, 10L, starter);
        registry.register(100L, 20L, participant);
        registry.register(200L, 30L, otherMeeting);
        TranscriptSseEventListener listener = new TranscriptSseEventListener(MeetingSseLoopback.publisher(registry));
        LocalDateTime recognizedAt = LocalDateTime.of(2026, 9, 23, 10, 15, 30);

        // when
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
        // then
        assertThat(starter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(expected))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:TRANSCRIPT_CREATED\n"));
        assertThat(participant.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(expected));
        assertThat(otherMeeting.sentData).isNull();
        assertThat(starter.completed).isFalse();
        assertThat(participant.completed).isFalse();
        assertThat(registry.find(100L, 10L)).contains(starter);
        assertThat(registry.find(100L, 20L)).contains(participant);
        assertThat(registry.find(200L, 30L)).contains(otherMeeting);
    }

    @Test
    @DisplayName("전사 SSE 이벤트는 DB Commit 이후 발행하도록 유지한다")
    void transcriptCreatedListenerRunsAfterCommit() throws Exception {
        // 테스트 목적:
        // 전사 SSE 이벤트는 DB Commit 이후 발행하도록 유지한다.

        // given
        Method method = TranscriptSseEventListener.class
                .getMethod("broadcastTranscriptCreated", TranscriptCreatedEvent.class);

        // when, then
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
