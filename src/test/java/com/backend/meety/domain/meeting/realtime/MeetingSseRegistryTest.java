package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseRegistryTest {

    @Test
    void separatesEmittersByMeetingAndUser() {
        MeetingSseRegistry registry = new MeetingSseRegistry();
        SseEmitter meetingOneUserOne = new SseEmitter();
        SseEmitter meetingOneUserTwo = new SseEmitter();
        SseEmitter meetingTwoUserOne = new SseEmitter();

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
}
