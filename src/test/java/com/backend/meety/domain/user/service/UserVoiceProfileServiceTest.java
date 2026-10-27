package com.backend.meety.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.backend.meety.domain.ai.client.SpeakerEmbeddingAiClient;
import com.backend.meety.domain.ai.client.SpeakerEmbeddingAiRequest;
import com.backend.meety.domain.ai.client.SpeakerEmbeddingAiResponse;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.exception.AudioFileException;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.user.dto.UserVoiceConsentResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileUploadUrlResponse;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.exception.UserErrorCode;
import com.backend.meety.domain.user.exception.UserException;
import com.backend.meety.domain.user.repository.UserRepository;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class UserVoiceProfileServiceTest {

    private static final Long USER_ID = 1L;
    private static final String STORAGE_KEY = "voice-profiles/1.mp4";
    private static final String CONTENT_TYPE = "audio/mp4";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T06:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 15, 0);
    private static final List<Double> EMBEDDING = List.of(0.12, -0.08, 0.31);
    private static final String MODEL_VERSION = "speaker-embedding-v1";

    @Mock
    private UserRepository userRepository;

    @Mock
    private AudioFileStorage audioFileStorage;

    @Mock
    private SpeakerEmbeddingAiClient speakerEmbeddingAiClient;

    private UserVoiceProfileService userVoiceProfileService;

    @BeforeEach
    void setUp() {
        userVoiceProfileService = new UserVoiceProfileService(
                userRepository, audioFileStorage, speakerEmbeddingAiClient, CLOCK);
    }

    @Test
    @DisplayName("처음 동의하면 현재 시각을 동의 시각으로 저장해 반환한다")
    void consent() {
        // 테스트 목적:
        // 동의 API가 현재 시각으로 동의를 기록하는지 검증한다.

        // given
        User user = User.create();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        // when
        UserVoiceConsentResponse response = userVoiceProfileService.consent(USER_ID);

        // then
        assertThat(response.consentedAt()).isEqualTo(NOW);
        assertThat(user.hasVoiceConsent()).isTrue();
    }

    @Test
    @DisplayName("이미 동의한 사용자는 처음 동의 시각을 그대로 반환한다")
    void consentIsIdempotent() {
        // 테스트 목적:
        // 동의를 여러 번 요청해도 처음 동의 시각이 바뀌지 않는지 검증한다.

        // given
        User user = User.create();
        LocalDateTime firstConsentedAt = NOW.minusDays(3);
        user.consentToVoiceCollection(firstConsentedAt);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        // when
        UserVoiceConsentResponse response = userVoiceProfileService.consent(USER_ID);

        // then
        assertThat(response.consentedAt()).isEqualTo(firstConsentedAt);
    }

    @Test
    @DisplayName("탈퇴했거나 없는 사용자의 동의 요청은 인증 오류로 거부한다")
    void consentWithoutActiveUser() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.consent(USER_ID))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("동의한 사용자에게 고정 키의 업로드 URL을 10분 유효로 발급한다")
    void createUploadUrl() throws Exception {
        // 테스트 목적:
        // 업로드 URL이 사용자별 고정 키(voice-profiles/{userId}.mp4)로 발급되고 만료 시각이 녹음 파일 정책과 같은지 검증한다.

        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(consentedUser()));
        given(audioFileStorage.createUploadUrl(STORAGE_KEY, CONTENT_TYPE, AudioFilePolicy.UPLOAD_URL_VALIDITY))
                .willReturn(url("https://bucket.s3.amazonaws.com/voice-profiles/1.mp4?sig"));

        // when
        UserVoiceProfileUploadUrlResponse response = userVoiceProfileService.createUploadUrl(USER_ID, CONTENT_TYPE);

        // then
        assertThat(response.uploadUrl()).isEqualTo("https://bucket.s3.amazonaws.com/voice-profiles/1.mp4?sig");
        assertThat(response.uploadUrlExpiresAt()).isEqualTo(NOW.plus(AudioFilePolicy.UPLOAD_URL_VALIDITY));
    }

    @Test
    @DisplayName("허용하지 않는 형식이면 업로드 URL을 발급하지 않는다")
    void createUploadUrlWithInvalidContentType() {
        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.createUploadUrl(USER_ID, "audio/webm"))
                .isInstanceOf(AudioFileException.class)
                .extracting("errorCode")
                .isEqualTo(AudioFileErrorCode.INVALID_AUDIO_CONTENT_TYPE);
        then(audioFileStorage).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("음성 수집에 동의하지 않은 사용자는 업로드 URL을 받을 수 없다")
    void createUploadUrlWithoutConsent() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(User.create()));

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.createUploadUrl(USER_ID, CONTENT_TYPE))
                .isInstanceOf(UserException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.VOICE_PROFILE_CONSENT_REQUIRED);
        then(audioFileStorage).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("S3 업로드 URL 발급에 실패하면 업로드 준비 실패로 응답한다")
    void createUploadUrlWhenPresignFails() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(consentedUser()));
        given(audioFileStorage.createUploadUrl(STORAGE_KEY, CONTENT_TYPE, AudioFilePolicy.UPLOAD_URL_VALIDITY))
                .willThrow(new IllegalStateException("presign failed"));

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.createUploadUrl(USER_ID, CONTENT_TYPE))
                .isInstanceOf(AudioFileException.class)
                .extracting("errorCode")
                .isEqualTo(AudioFileErrorCode.AUDIO_UPLOAD_URL_CREATE_FAILED);
    }

    @Test
    @DisplayName("업로드된 음성의 다운로드 URL을 AI에 보내 받은 벡터와 버전을 저장한다")
    void register() throws Exception {
        // 테스트 목적:
        // 등록 시 S3 파일 확인 → 다운로드 URL로 AI 호출 → 고정 키·벡터·버전 저장 순서로 처리되는지 검증한다.

        // given
        User user = consentedUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(audioFileStorage.findObjectSize(STORAGE_KEY)).willReturn(Optional.of(48_000L));
        given(audioFileStorage.createDownloadUrl(STORAGE_KEY, AudioFilePolicy.DOWNLOAD_URL_VALIDITY))
                .willReturn(url("https://bucket.s3.amazonaws.com/voice-profiles/1.mp4?download"));
        given(speakerEmbeddingAiClient.requestEmbedding(
                new SpeakerEmbeddingAiRequest("https://bucket.s3.amazonaws.com/voice-profiles/1.mp4?download")))
                .willReturn(new SpeakerEmbeddingAiResponse(MODEL_VERSION, EMBEDDING));

        // when
        UserVoiceProfileResponse response = userVoiceProfileService.register(USER_ID);

        // then
        assertThat(response.hasVoiceProfile()).isTrue();
        then(userRepository).should().save(user);
        assertThat(user.getVoiceSampleKey()).isEqualTo(STORAGE_KEY);
        assertThat(user.getVoiceEmbedding()).isEqualTo(EMBEDDING);
        assertThat(user.getEmbeddingVersion()).isEqualTo(MODEL_VERSION);
    }

    @Test
    @DisplayName("음성 수집에 동의하지 않은 사용자는 등록할 수 없다")
    void registerWithoutConsent() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(User.create()));

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.register(USER_ID))
                .isInstanceOf(UserException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.VOICE_PROFILE_CONSENT_REQUIRED);
        then(speakerEmbeddingAiClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("탈퇴한 사용자는 등록할 수 없다")
    void registerWithWithdrawnUser() {
        // given
        User user = consentedUser();
        user.withdraw(NOW);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.register(USER_ID))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("S3에 업로드된 음성이 없으면 AI를 호출하지 않고 거부한다")
    void registerWithoutUploadedObject() {
        // given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(consentedUser()));
        given(audioFileStorage.findObjectSize(STORAGE_KEY)).willReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.register(USER_ID))
                .isInstanceOf(AudioFileException.class)
                .extracting("errorCode")
                .isEqualTo(AudioFileErrorCode.AUDIO_OBJECT_NOT_FOUND);
        then(speakerEmbeddingAiClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("AI 호출이 실패하거나 시간 초과되면 기존 등록을 유지하고 추출 실패로 응답한다")
    void registerWhenAiFails() throws Exception {
        // 테스트 목적:
        // AI 오류·타임아웃은 원인 구분 없이 502로 응답하고, 저장을 시도하지 않아 기존 벡터가 유지되는지 검증한다.

        // given
        givenUploadedObject();
        given(speakerEmbeddingAiClient.requestEmbedding(any()))
                .willThrow(new ResourceAccessException("Read timed out"));

        // when, then
        assertThatThrownBy(() -> userVoiceProfileService.register(USER_ID))
                .isInstanceOf(UserException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.VOICE_PROFILE_EXTRACTION_FAILED);
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("AI가 빈 벡터나 유효하지 않은 숫자를 주면 저장하지 않고 추출 실패로 응답한다")
    void registerWithInvalidAiResponse() throws Exception {
        // 테스트 목적:
        // 빈 배열, NaN, 버전 누락 응답을 성공으로 저장하지 않는지 검증한다.

        // given
        givenUploadedObject();
        given(speakerEmbeddingAiClient.requestEmbedding(any())).willReturn(
                new SpeakerEmbeddingAiResponse(MODEL_VERSION, List.of()),
                new SpeakerEmbeddingAiResponse(MODEL_VERSION, List.of(0.1, Double.NaN)),
                new SpeakerEmbeddingAiResponse(" ", EMBEDDING));

        // when, then
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> userVoiceProfileService.register(USER_ID))
                    .isInstanceOf(UserException.class)
                    .extracting("errorCode")
                    .isEqualTo(UserErrorCode.VOICE_PROFILE_EXTRACTION_FAILED);
        }
        then(userRepository).should(never()).save(any());
    }

    private void givenUploadedObject() throws Exception {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(consentedUser()));
        given(audioFileStorage.findObjectSize(STORAGE_KEY)).willReturn(Optional.of(48_000L));
        given(audioFileStorage.createDownloadUrl(STORAGE_KEY, AudioFilePolicy.DOWNLOAD_URL_VALIDITY))
                .willReturn(url("https://bucket.s3.amazonaws.com/voice-profiles/1.mp4?download"));
    }

    private User consentedUser() {
        User user = User.create();
        user.consentToVoiceCollection(NOW.minusDays(1));
        return user;
    }

    private URL url(String value) throws Exception {
        return URI.create(value).toURL();
    }
}
