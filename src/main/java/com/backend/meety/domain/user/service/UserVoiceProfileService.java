package com.backend.meety.domain.user.service;

import com.backend.meety.domain.ai.client.SpeakerEmbeddingAiClient;
import com.backend.meety.domain.ai.client.SpeakerEmbeddingAiRequest;
import com.backend.meety.domain.ai.client.SpeakerEmbeddingAiResponse;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.exception.AudioFileException;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.user.VoiceProfilePolicy;
import com.backend.meety.domain.user.dto.UserVoiceConsentResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileUploadUrlResponse;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.exception.UserErrorCode;
import com.backend.meety.domain.user.exception.UserException;
import com.backend.meety.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserVoiceProfileService {

    private final UserRepository userRepository;
    private final AudioFileStorage audioFileStorage;
    private final SpeakerEmbeddingAiClient speakerEmbeddingAiClient;
    private final Clock clock;

    @Transactional
    public UserVoiceConsentResponse consent(Long userId) {
        User user = findActiveUser(userId);
        return new UserVoiceConsentResponse(user.consentToVoiceCollection(LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public UserVoiceProfileUploadUrlResponse createUploadUrl(Long userId, String contentType) {
        if (!AudioFilePolicy.isAllowedContentType(contentType)) {
            throw new AudioFileException(AudioFileErrorCode.INVALID_AUDIO_CONTENT_TYPE);
        }
        validateConsented(findActiveUser(userId));
        LocalDateTime now = LocalDateTime.now(clock);
        return new UserVoiceProfileUploadUrlResponse(
                uploadUrl(userId, contentType),
                now.plus(AudioFilePolicy.UPLOAD_URL_VALIDITY)
        );
    }

    public UserVoiceProfileResponse register(Long userId) {
        User user = findActiveUser(userId);
        validateConsented(user);
        String storageKey = VoiceProfilePolicy.storageKey(userId);
        if (audioFileStorage.findObjectSize(storageKey).isEmpty()) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_OBJECT_NOT_FOUND);
        }
        SpeakerEmbeddingAiResponse response = requestEmbedding(userId, storageKey);
        user.registerVoiceProfile(storageKey, response.embedding(), response.modelVersion());
        userRepository.save(user);
        return new UserVoiceProfileResponse(true);
    }

    private User findActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isWithdrawn())
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED));
    }

    private void validateConsented(User user) {
        if (!user.hasVoiceConsent()) {
            throw new UserException(UserErrorCode.VOICE_PROFILE_CONSENT_REQUIRED);
        }
    }

    private String uploadUrl(Long userId, String contentType) {
        try {
            return audioFileStorage.createUploadUrl(
                    VoiceProfilePolicy.storageKey(userId), contentType, AudioFilePolicy.UPLOAD_URL_VALIDITY).toString();
        } catch (Exception e) {
            log.error("사전 녹음 S3 presigned 업로드 URL 발급에 실패했습니다. userId={}", userId, e);
            throw new AudioFileException(AudioFileErrorCode.AUDIO_UPLOAD_URL_CREATE_FAILED);
        }
    }

    private SpeakerEmbeddingAiResponse requestEmbedding(Long userId, String storageKey) {
        String audioUrl = audioFileStorage.createDownloadUrl(storageKey, AudioFilePolicy.DOWNLOAD_URL_VALIDITY)
                .toString();
        SpeakerEmbeddingAiResponse response;
        try {
            response = speakerEmbeddingAiClient.requestEmbedding(new SpeakerEmbeddingAiRequest(audioUrl));
        } catch (RestClientException e) {
            log.warn("AI 화자 임베딩 추출에 실패했습니다. userId={}", userId, e);
            throw new UserException(UserErrorCode.VOICE_PROFILE_EXTRACTION_FAILED);
        }
        if (!isValid(response)) {
            log.warn("AI 화자 임베딩 응답이 올바르지 않습니다. userId={}", userId);
            throw new UserException(UserErrorCode.VOICE_PROFILE_EXTRACTION_FAILED);
        }
        return response;
    }

    private boolean isValid(SpeakerEmbeddingAiResponse response) {
        return response != null
                && response.modelVersion() != null && !response.modelVersion().isBlank()
                && response.embedding() != null && !response.embedding().isEmpty()
                && response.embedding().stream().allMatch(value -> value != null && Double.isFinite(value));
    }
}
