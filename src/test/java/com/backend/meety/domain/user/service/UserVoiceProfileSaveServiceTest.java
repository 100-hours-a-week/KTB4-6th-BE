package com.backend.meety.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserVoiceProfileSaveServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserVoiceProfileSaveService userVoiceProfileSaveService;

    @Test
    @DisplayName("재녹음하면 음성 키·벡터·버전을 새 값으로 함께 교체한다")
    void saveReplacesVoiceProfile() {
        // 테스트 목적:
        // 세 컬럼이 항상 함께 바뀌어 이전 벡터와 새 버전이 섞이지 않는지 검증한다.

        // given
        User user = User.create();
        user.registerVoiceProfile("voice-profiles/1.mp4", List.of(0.5), "speaker-embedding-v1");
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        // when
        userVoiceProfileSaveService.save(1L, "voice-profiles/1.mp4", List.of(0.1, -0.2), "speaker-embedding-v2");

        // then
        assertThat(user.getVoiceSampleKey()).isEqualTo("voice-profiles/1.mp4");
        assertThat(user.getVoiceEmbedding()).containsExactly(0.1, -0.2);
        assertThat(user.getEmbeddingVersion()).isEqualTo("speaker-embedding-v2");
    }

    @Test
    @DisplayName("AI를 기다리는 사이 탈퇴한 사용자에게는 저장하지 않는다")
    void saveSkipsWithdrawnUser() {
        // 테스트 목적:
        // 저장 시점에 탈퇴한 사용자면 벡터를 저장하지 않고 인증 오류로 끝나는지 검증한다.

        // given
        User user = User.create();
        user.withdraw(LocalDateTime.now());
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        // when, then
        assertThatThrownBy(() -> userVoiceProfileSaveService.save(
                1L, "voice-profiles/1.mp4", List.of(0.1), "speaker-embedding-v1"))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.AUTHENTICATION_REQUIRED);
        assertThat(user.hasVoiceProfile()).isFalse();
    }
}
