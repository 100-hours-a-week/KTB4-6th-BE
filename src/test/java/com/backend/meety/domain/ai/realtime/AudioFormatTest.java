package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioFormatTest {

    @Test
    void supportsOnlyContractedFormats() {
        assertThat(AudioFormat.supports("webm_opus")).isTrue();
        assertThat(AudioFormat.supports("mp4_aac")).isTrue();
        assertThat(AudioFormat.supports("pcm")).isFalse();
        assertThat(AudioFormat.supports(null)).isFalse();
    }
}
