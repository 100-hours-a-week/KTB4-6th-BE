package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AudioStreamStateTest {

    @Test
    @DisplayName("복구 순번이 더 클 때만 처리 순번을 올린다")
    void restoreKeepsLargerSequence() {
        AudioStreamState state = new AudioStreamState();
        state.markProcessed(100L);

        state.restore(80L);
        assertThat(state.lastProcessedSequence()).isEqualTo(100L);

        state.restore(150L);
        assertThat(state.lastProcessedSequence()).isEqualTo(150L);
    }
}
