package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseRegistryTest {

    @Test
    @DisplayName("회의·사용자별로 emitter를 분리해 관리한다")
    void separatesEmittersByMeetingAndUser() {
        // 테스트 목적:
        // 회의·사용자별로 emitter를 분리해 관리한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter meetingOneUserOne = new TestSseEmitter();
        TestSseEmitter meetingOneUserTwo = new TestSseEmitter();
        TestSseEmitter meetingTwoUserOne = new TestSseEmitter();

        // when
        registry.register(100L, 10L, meetingOneUserOne);
        registry.register(100L, 20L, meetingOneUserTwo);
        registry.register(200L, 10L, meetingTwoUserOne);

        // then
        assertThat(registry.find(100L, 10L)).contains(meetingOneUserOne);
        assertThat(registry.find(100L, 20L)).contains(meetingOneUserTwo);
        assertThat(registry.find(200L, 10L)).contains(meetingTwoUserOne);
        assertThat(registry.count(100L)).isEqualTo(2);
        assertThat(registry.count(200L)).isOne();
        assertThat(registry.countAll()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 회의·사용자의 재연결은 이전 emitter를 교체한다")
    void replacesSameMeetingUserEmitterAndReturnsPreviousEmitter() {
        // 테스트 목적:
        // 같은 회의·사용자의 재연결은 이전 emitter를 교체한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        SseEmitter previous = new SseEmitter();
        SseEmitter current = new SseEmitter();

        // when, then
        assertThat(registry.register(100L, 10L, previous)).isEmpty();

        assertThat(registry.register(100L, 10L, current)).contains(previous);
        assertThat(registry.find(100L, 10L)).contains(current);
        assertThat(registry.count(100L)).isOne();
    }

    @Test
    @DisplayName("이전 emitter 제거가 새 연결을 지우지 않고 반복 제거도 안전하다")
    void removeIsIdempotentAndDoesNotRemoveCurrentEmitterWithStaleEmitter() {
        // 테스트 목적:
        // 이전 emitter 제거가 새 연결을 지우지 않고 반복 제거도 안전하다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        SseEmitter stale = new SseEmitter();
        SseEmitter current = new SseEmitter();
        registry.register(100L, 10L, current);

        // when
        registry.remove(100L, 10L, stale);

        // then
        assertThat(registry.find(100L, 10L)).contains(current);

        registry.remove(100L, 10L, current);
        registry.remove(100L, 10L, current);

        assertThat(registry.find(100L, 10L)).isEmpty();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("한 참가자의 연결 종료는 다른 참가자 연결을 유지한다")
    void completeOneEmitterKeepsOtherParticipantsInSameMeeting() {
        // 테스트 목적:
        // 한 참가자의 연결 종료는 다른 참가자 연결을 유지한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        // when
        registry.complete(100L, 20L);

        // then
        assertThat(user20.completed).isTrue();
        assertThat(registry.find(100L, 20L)).isEmpty();
        assertThat(registry.find(100L, 10L)).contains(user10);
        assertThat(registry.find(100L, 30L)).contains(user30);
        assertThat(registry.count(100L)).isEqualTo(2);
    }

    @Test
    @DisplayName("존재하지 않는 참가자 연결 종료는 오류 없이 무시한다")
    void completeMissingEmitterIsNoop() {
        // 테스트 목적:
        // 존재하지 않는 참가자 연결 종료는 오류 없이 무시한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());

        // when
        registry.complete(100L, 10L);

        // then
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("전체 종료는 개별 종료 실패와 무관하게 모든 연결을 정리한다")
    void completeAllRemovesMeetingAndCompletesEachEmitterIndependently() {
        // 테스트 목적:
        // 전체 종료는 개별 종료 실패와 무관하게 모든 연결을 정리한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        user20.failComplete = true;
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        // when
        registry.completeAll(100L);

        // then
        assertThat(user10.completed).isTrue();
        assertThat(user20.completeAttempted).isTrue();
        assertThat(user30.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("회의 삭제 이벤트를 전송한 뒤 전체 연결을 종료한다")
    void sendAndCompleteAllSendsEventThenCompletesAndRemovesMeeting() {
        // 테스트 목적:
        // 회의 삭제 이벤트를 전송한 뒤 전체 연결을 종료한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        MeetingSseDeletedEvent event = MeetingSseDeletedEvent.deleted(100L);
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        // when
        registry.sendAndCompleteAll(100L, "MEETING_DELETED", event);

        // then
        assertThat(user10.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event)
                .anyMatch(data -> data instanceof String value && value.startsWith("event:MEETING_DELETED\n"));
        assertThat(user20.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(user30.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(user30.completed).isTrue();
        assertThat(user10.sentBeforeComplete).isTrue();
        assertThat(user20.sentBeforeComplete).isTrue();
        assertThat(user30.sentBeforeComplete).isTrue();
        assertThat(registry.count(100L)).isZero();
    }

    @Test
    @DisplayName("삭제 이벤트 전송 실패가 다른 참가자 전송과 전체 정리를 막지 않는다")
    void sendAndCompleteAllContinuesWhenOneEmitterSendFails() {
        // 테스트 목적:
        // 삭제 이벤트 전송 실패가 다른 참가자 전송과 전체 정리를 막지 않는다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        user20.sendFailure = new IOException("send failed");
        MeetingSseDeletedEvent event = MeetingSseDeletedEvent.deleted(100L);
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        // when
        registry.sendAndCompleteAll(100L, "MEETING_DELETED", event);

        // then
        assertThat(user10.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(user20.sendAttempted).isTrue();
        assertThat(user30.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(user10.completed).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(user30.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("연결이 없는 회의의 삭제 이벤트와 종료는 안전하게 무시한다")
    void sendAndCompleteAllMissingMeetingIsNoop() {
        // 테스트 목적:
        // 연결이 없는 회의의 삭제 이벤트와 종료는 안전하게 무시한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());

        // when
        registry.sendAndCompleteAll(100L, "MEETING_DELETED", MeetingSseDeletedEvent.deleted(100L));

        // then
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("브로드캐스트는 해당 회의의 모든 연결에 전송하고 연결을 유지한다")
    void broadcastSendsEventToAllMeetingEmittersAndKeepsConnections() {
        // 테스트 목적:
        // 브로드캐스트는 해당 회의의 모든 연결에 전송하고 연결을 유지한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter otherMeetingUser = new TestSseEmitter();
        Object event = "transcript";
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(200L, 30L, otherMeetingUser);

        // when
        int emitterCount = registry.broadcast(100L, "TRANSCRIPT_CREATED", event);

        // then
        assertThat(emitterCount).isEqualTo(2);
        assertThat(user10.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event)
                .anyMatch(data -> data instanceof String value && value.startsWith("event:TRANSCRIPT_CREATED\n"));
        assertThat(user20.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(otherMeetingUser.sentData).isNull();
        assertThat(user10.completed).isFalse();
        assertThat(user20.completed).isFalse();
        assertThat(registry.find(100L, 10L)).contains(user10);
        assertThat(registry.find(100L, 20L)).contains(user20);
        assertThat(registry.find(200L, 30L)).contains(otherMeetingUser);
    }

    @Test
    @DisplayName("전송 실패 emitter만 제거하고 나머지 참가자 전송을 계속한다")
    void broadcastRemovesOnlyFailedEmitterAndContinues() {
        // 테스트 목적:
        // 전송 실패 emitter만 제거하고 나머지 참가자 전송을 계속한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        user20.sendFailure = new IOException("send failed");
        Object event = "transcript";
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        // when
        int emitterCount = registry.broadcast(100L, "TRANSCRIPT_CREATED", event);

        // then
        assertThat(emitterCount).isEqualTo(3);
        assertThat(user10.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(user20.sendAttempted).isTrue();
        assertThat(user20.completed).isTrue();
        assertThat(user30.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(user10.completed).isFalse();
        assertThat(user30.completed).isFalse();
        assertThat(registry.find(100L, 10L)).contains(user10);
        assertThat(registry.find(100L, 20L)).isEmpty();
        assertThat(registry.find(100L, 30L)).contains(user30);
    }

    @Test
    @DisplayName("전체 전송·종료 이후 늦은 제거 콜백은 안전하다")
    void callbackRemoveAfterSendAndCompleteAllIsSafe() {
        // 테스트 목적:
        // 전체 전송·종료 이후 늦은 제거 콜백은 안전하다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter emitter = new TestSseEmitter();
        registry.register(100L, 10L, emitter);

        // when
        registry.sendAndCompleteAll(100L, "MEETING_DELETED", MeetingSseDeletedEvent.deleted(100L));
        registry.remove(100L, 10L, emitter);

        // then
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("명시적 종료 이후 늦은 제거 콜백은 안전하다")
    void callbackRemoveAfterExplicitCompleteIsSafe() {
        // 테스트 목적:
        // 명시적 종료 이후 늦은 제거 콜백은 안전하다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter emitter = new TestSseEmitter();
        registry.register(100L, 10L, emitter);

        // when
        registry.complete(100L, 10L);
        registry.remove(100L, 10L, emitter);

        // then
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("클라이언트가 끊긴 emitter의 브로드캐스트 실패도 해당 emitter만 제거한다")
    void broadcastClientDisconnectRemovesOnlyDisconnectedEmitter() {
        // 테스트 목적:
        // 브라우저가 이미 떠나 전송이 AsyncRequestNotUsableException으로 실패해도
        // 기존 전송 실패와 같이 해당 연결만 정리되고 나머지 전송은 계속되는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        TestSseEmitter alive = new TestSseEmitter();
        TestSseEmitter disconnected = new TestSseEmitter();
        disconnected.sendFailure = new AsyncRequestNotUsableException("Broken pipe");
        Object event = "transcript";
        registry.register(100L, 10L, disconnected);
        registry.register(100L, 20L, alive);

        // when
        int emitterCount = registry.broadcast(100L, "TRANSCRIPT_CREATED", event);

        // then
        assertThat(emitterCount).isEqualTo(2);
        assertThat(disconnected.completed).isTrue();
        assertThat(alive.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(event);
        assertThat(registry.find(100L, 10L)).isEmpty();
        assertThat(registry.find(100L, 20L)).contains(alive);
    }

    private static class TestSseEmitter extends SseEmitter {

        private boolean completed;
        private boolean completeAttempted;
        private boolean failComplete;
        private boolean sendAttempted;
        private boolean sentBeforeComplete;
        private IOException sendFailure;
        private Set<ResponseBodyEmitter.DataWithMediaType> sentData;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempted = true;
            sentBeforeComplete = !completeAttempted;
            if (sendFailure != null) {
                throw sendFailure;
            }
            sentData = builder.build();
        }

        @Override
        public void complete() {
            completeAttempted = true;
            if (failComplete) {
                throw new IllegalStateException("complete failed");
            }
            completed = true;
        }
    }
}
