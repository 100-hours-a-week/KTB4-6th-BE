package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
class MeetingSseRegistryTest {

    private final MeetingSseRegistry registry = new MeetingSseRegistry();

    @Test
    void separatesEmittersByMeeting() {
        registry.register(1L);
        registry.register(2L);

        assertThat(registry.count(1L)).isEqualTo(1);
        assertThat(registry.count(2L)).isEqualTo(1);
    }

    @Test
    void completeMeetingRemovesEveryEmitter() {
        registry.register(1L);
        registry.register(1L);

        registry.completeMeeting(1L);

        assertThat(registry.count(1L)).isZero();
    }
}
