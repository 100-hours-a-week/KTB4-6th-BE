package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.event.MeetingDeletedEvent;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseCleanupEventListenerTest {

    @Test
    @DisplayName("참가자 퇴장 이벤트는 해당 참가자의 SSE만 종료한다")
    void leftEventClosesOnlyParticipantEmitter() {
        // 테스트 목적:
        // 참가자 퇴장 이벤트는 해당 참가자의 SSE만 종료한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(MeetingSseLoopback.publisher(registry));

        // when
        listener.closeParticipantSse(new MeetingParticipantLeftEvent(100L, 10L));

        // then
        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isFalse();
        assertThat(registry.find(100L, 10L)).isEmpty();
        assertThat(registry.find(100L, 20L)).contains(user20);
    }

    @Test
    @DisplayName("회의 완료 이벤트는 회의의 모든 SSE 연결을 종료한다")
    void completedEventClosesAllMeetingEmitters() {
        // 테스트 목적:
        // 회의 완료 이벤트는 회의의 모든 SSE 연결을 종료한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(MeetingSseLoopback.publisher(registry));

        // when
        listener.closeMeetingSse(new MeetingCompletedEvent(100L));

        // then
        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
    }

    @Test
    @DisplayName("회의 삭제 이벤트를 전달한 뒤 회의 SSE를 종료한다")
    void deletedEventSendsDeletedEventAndClosesAllMeetingEmitters() {
        // 테스트 목적:
        // 회의 삭제 이벤트를 전달한 뒤 회의 SSE를 종료한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(MeetingSseLoopback.publisher(registry));

        // when
        listener.sendDeletedEventAndCloseMeetingSse(new MeetingDeletedEvent(100L));

        // then
        assertThat(user10.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(MeetingSseDeletedEvent.deleted(100L)))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:MEETING_DELETED\n"));
        assertThat(user20.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseLoopback.json(MeetingSseDeletedEvent.deleted(100L)));
        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
    }

    @Test
    @DisplayName("연결이 없는 회의의 삭제 후 정리도 안전하다")
    void deletedEventWithNoEmitterIsNoop() {
        // 테스트 목적:
        // 연결이 없는 회의의 삭제 후 정리도 안전하다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        MeetingSseCleanupEventListener listener = new MeetingSseCleanupEventListener(MeetingSseLoopback.publisher(registry));

        // when
        listener.sendDeletedEventAndCloseMeetingSse(new MeetingDeletedEvent(100L));

        // then
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("회의 SSE 정리는 DB Commit 이후 실행하도록 유지한다")
    void cleanupListenersRunAfterCommit() throws Exception {
        // 테스트 목적:
        // 회의 SSE 정리는 DB Commit 이후 실행하도록 유지한다.

        // given
        Method left = MeetingSseCleanupEventListener.class
                .getMethod("closeParticipantSse", MeetingParticipantLeftEvent.class);
        Method completed = MeetingSseCleanupEventListener.class
                .getMethod("closeMeetingSse", MeetingCompletedEvent.class);
        Method deleted = MeetingSseCleanupEventListener.class
                .getMethod("sendDeletedEventAndCloseMeetingSse", MeetingDeletedEvent.class);

        // when, then
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
