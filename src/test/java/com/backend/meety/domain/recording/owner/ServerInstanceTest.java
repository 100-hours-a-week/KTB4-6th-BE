package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServerInstanceTest {

    @Test
    @DisplayName("Owner 값은 서버 ID와 핸드셰이크마다 1씩 오르는 연결 ID로 만든다")
    void nextOwnerCombinesServerIdAndConnectionId() {
        ServerInstance serverInstance = new ServerInstance("server-a");

        assertThat(serverInstance.nextOwner()).isEqualTo("server-a:1");
        assertThat(serverInstance.nextOwner()).isEqualTo("server-a:2");
    }

    @Test
    @DisplayName("기동할 때마다 다른 서버 ID를 만든다")
    void createsDifferentServerIdPerInstance() {
        assertThat(new ServerInstance().serverId()).isNotEqualTo(new ServerInstance().serverId());
    }
}
