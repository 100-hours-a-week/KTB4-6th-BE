package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingCommandChannelsTest {

    @Test
    @DisplayName("Owner 값의 serverId로 그 서버의 명령 채널을 찾는다")
    void resolvesChannelFromOwner() {
        String serverId = "3f2a9c1e-8b4d-4e7a-9f21-6c0d5e8a7b13";

        assertThat(RecordingCommandChannels.ofOwner(serverId + ":12"))
                .isEqualTo(RecordingCommandChannels.of(serverId))
                .isEqualTo("recording-command:" + serverId);
    }
}
