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
import tools.jackson.databind.JsonNode;

class TranscriptSseEventListenerTest {

    @Test
    @DisplayName("저장된 전사의 AI speakerId를 기존 speaker 필드와 함께 해당 회의 참가자에게 전달한다")
    void transcriptCreatedEventBroadcastsToAllMeetingEmitters() {
        // 테스트 목적:
        // 저장된 전사의 AI speakerId가 0이어도 기존 speaker 필드를 유지하며 회의 참가자에게 전달하는지 검증한다.

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
                recognizedAt,
                0L
        ));

        TranscriptCreatedSseEvent expected = TranscriptCreatedSseEvent.of(
                100L, 900L, 31L, "final text", 12000L, 14500L, recognizedAt, 0L);
        // then
        assertThat(expected.segment().speaker()).isNull();
        assertThat(expected.segment().speakerId()).isZero();
        assertThat(starter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(expected))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:TRANSCRIPT_CREATED\n"));
        assertThat(participant.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(expected));
        JsonNode deliveredSegment = starter.sentData.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(JsonNode.class::isInstance)
                .map(JsonNode.class::cast)
                .findFirst()
                .orElseThrow()
                .path("segment");
        assertThat(deliveredSegment.path("speakerId").asLong()).isZero();
        assertThat(deliveredSegment.path("speaker").isNull()).isTrue();
        assertThat(otherMeeting.sentData).isNull();
        assertThat(starter.completed).isFalse();
        assertThat(participant.completed).isFalse();
        assertThat(registry.find(100L, 10L)).contains(starter);
        assertThat(registry.find(100L, 20L)).contains(participant);
        assertThat(registry.find(200L, 30L)).contains(otherMeeting);
    }

    @Test
    @DisplayName("AI speakerId가 없으면 전사 SSE의 speakerId는 null이며 기존 speaker 필드를 유지한다")
    void missingSpeakerIdKeepsExistingPayload() {
        // 테스트 목적:
        // AI 화자 식별자가 누락된 전사도 기존 SSE 필드와 함께 안전하게 전달되는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter emitter = new TestSseEmitter();
        registry.register(100L, 10L, emitter);
        TranscriptSseEventListener listener = new TranscriptSseEventListener(MeetingSseLoopback.publisher(registry));
        LocalDateTime recognizedAt = LocalDateTime.of(2026, 9, 23, 10, 15, 30);

        // when
        listener.broadcastTranscriptCreated(new TranscriptCreatedEvent(
                100L, 900L, 31L, "final text", 12000L, 14500L, recognizedAt, null));

        // then
        TranscriptCreatedSseEvent expected = TranscriptCreatedSseEvent.of(
                100L, 900L, 31L, "final text", 12000L, 14500L, recognizedAt, null);
        assertThat(expected.segment().speaker()).isNull();
        assertThat(expected.segment().speakerId()).isNull();
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(expected));
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
