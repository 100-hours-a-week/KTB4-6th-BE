package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseRegistryTest {

    @Test
    void separatesEmittersByMeetingAndUser() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter meetingOneUserOne = new TestSseEmitter();
        TestSseEmitter meetingOneUserTwo = new TestSseEmitter();
        TestSseEmitter meetingTwoUserOne = new TestSseEmitter();

        registry.register(100L, 10L, meetingOneUserOne);
        registry.register(100L, 20L, meetingOneUserTwo);
        registry.register(200L, 10L, meetingTwoUserOne);

        assertThat(registry.find(100L, 10L)).contains(meetingOneUserOne);
        assertThat(registry.find(100L, 20L)).contains(meetingOneUserTwo);
        assertThat(registry.find(200L, 10L)).contains(meetingTwoUserOne);
        assertThat(registry.count(100L)).isEqualTo(2);
        assertThat(registry.count(200L)).isOne();
        assertThat(registry.countAll()).isEqualTo(3);
    }

    @Test
    void replacesSameMeetingUserEmitterAndReturnsPreviousEmitter() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        SseEmitter previous = new SseEmitter();
        SseEmitter current = new SseEmitter();

        assertThat(registry.register(100L, 10L, previous)).isEmpty();

        assertThat(registry.register(100L, 10L, current)).contains(previous);
        assertThat(registry.find(100L, 10L)).contains(current);
        assertThat(registry.count(100L)).isOne();
    }

    @Test
    void removeIsIdempotentAndDoesNotRemoveCurrentEmitterWithStaleEmitter() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        SseEmitter stale = new SseEmitter();
        SseEmitter current = new SseEmitter();
        registry.register(100L, 10L, current);

        registry.remove(100L, 10L, stale);

        assertThat(registry.find(100L, 10L)).contains(current);

        registry.remove(100L, 10L, current);
        registry.remove(100L, 10L, current);

        assertThat(registry.find(100L, 10L)).isEmpty();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    void completeOneEmitterKeepsOtherParticipantsInSameMeeting() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        registry.complete(100L, 20L);

        assertThat(user20.completed).isTrue();
        assertThat(registry.find(100L, 20L)).isEmpty();
        assertThat(registry.find(100L, 10L)).contains(user10);
        assertThat(registry.find(100L, 30L)).contains(user30);
        assertThat(registry.count(100L)).isEqualTo(2);
    }

    @Test
    void completeMissingEmitterIsNoop() {
        MeetingSseRegistry registry = new MeetingSseRegistry();

        registry.complete(100L, 10L);

        assertThat(registry.countAll()).isZero();
    }

    @Test
    void completeAllRemovesMeetingAndCompletesEachEmitterIndependently() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter user10 = new TestSseEmitter();
        TestSseEmitter user20 = new TestSseEmitter();
        TestSseEmitter user30 = new TestSseEmitter();
        user20.failComplete = true;
        registry.register(100L, 10L, user10);
        registry.register(100L, 20L, user20);
        registry.register(100L, 30L, user30);

        registry.completeAll(100L);

        assertThat(user10.completed).isTrue();
        assertThat(user20.completeAttempted).isTrue();
        assertThat(user30.completed).isTrue();
        assertThat(registry.count(100L)).isZero();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    void callbackRemoveAfterExplicitCompleteIsSafe() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        TestSseEmitter emitter = new TestSseEmitter();
        registry.register(100L, 10L, emitter);

        registry.complete(100L, 10L);
        registry.remove(100L, 10L, emitter);

        assertThat(registry.countAll()).isZero();
    }

    private static class TestSseEmitter extends SseEmitter {

        private boolean completed;
        private boolean completeAttempted;
        private boolean failComplete;

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
