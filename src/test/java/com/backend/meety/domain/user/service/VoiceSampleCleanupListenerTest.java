package com.backend.meety.domain.user.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.user.event.UserWithdrawnEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceSampleCleanupListenerTest {

    @Mock
    private AudioFileStorage audioFileStorage;

    @InjectMocks
    private VoiceSampleCleanupListener listener;

    @Test
    @DisplayName("탈퇴하면 사용자의 고정 키 음성 파일을 S3에서 삭제한다")
    void deleteVoiceSample() {
        // when
        listener.deleteVoiceSample(new UserWithdrawnEvent(1L));

        // then
        then(audioFileStorage).should().deleteObject("voice-profiles/1.mp4");
    }

    @Test
    @DisplayName("S3 삭제에 실패해도 예외를 던지지 않고 로그만 남긴다")
    void deleteVoiceSampleFailureIsLogged() {
        // given
        willThrow(new IllegalStateException("s3 down")).given(audioFileStorage).deleteObject("voice-profiles/1.mp4");

        // when, then
        assertThatCode(() -> listener.deleteVoiceSample(new UserWithdrawnEvent(1L))).doesNotThrowAnyException();
    }
}
