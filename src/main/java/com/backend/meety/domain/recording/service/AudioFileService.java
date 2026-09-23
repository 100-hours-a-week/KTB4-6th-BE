package com.backend.meety.domain.recording.service;

import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.exception.AudioFileException;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import java.net.URL;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioFileService {

    private final RecordingSessionRepository recordingRepository;
    private final AudioFileRepository audioFileRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final AudioFileStorage audioFileStorage;
    private final Clock clock;

    @Transactional
    public AudioFileUploadUrlResponse createUploadUrl(Long userId, Long recordingSessionId, String contentType) {
        if (!AudioFilePolicy.isAllowedContentType(contentType)) {
            throw new AudioFileException(AudioFileErrorCode.INVALID_AUDIO_CONTENT_TYPE);
        }
        RecordingSession session = recordingRepository.findByIdAndDeletedAtIsNull(recordingSessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        validateStarter(userId, session);

        // TODO: audio_files.recording_session_id에 UNIQUE가 없어 동시 요청 시 중복 발급이 가능하다. 스키마 변경 확정 후 제약을 추가한다.
        if (audioFileRepository.existsByRecordingSessionIdAndDeletedAtIsNull(recordingSessionId)) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_ALREADY_EXISTS);
        }

        AudioFile audioFile = audioFileRepository.save(
                AudioFile.create(session, storageKey(session), contentType));
        LocalDateTime now = LocalDateTime.now(clock);
        return new AudioFileUploadUrlResponse(
                audioFile.getId(),
                uploadUrl(audioFile),
                now.plus(AudioFilePolicy.UPLOAD_URL_VALIDITY)
        );
    }

    private void validateStarter(Long userId, RecordingSession session) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        session.getMeeting().getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new AudioFileException(AudioFileErrorCode.AUDIO_FILE_CREATE_FORBIDDEN));
        if (!session.getStartedByTeamMember().getId().equals(member.getId())) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_CREATE_FORBIDDEN);
        }
    }

    private String storageKey(RecordingSession session) {
        return "recordings/%d/%d/%s.%s".formatted(
                session.getMeeting().getId(),
                session.getId(),
                UUID.randomUUID(),
                AudioFilePolicy.EXTENSION
        );
    }

    private String uploadUrl(AudioFile audioFile) {
        try {
            URL url = audioFileStorage.createUploadUrl(
                    audioFile.getStorageKey(), audioFile.getContentType(), AudioFilePolicy.UPLOAD_URL_VALIDITY);
            return url.toString();
        } catch (Exception e) {
            log.error("S3 presigned 업로드 URL 발급에 실패했습니다. audioFileId={}", audioFile.getId(), e);
            throw new AudioFileException(AudioFileErrorCode.AUDIO_UPLOAD_URL_CREATE_FAILED);
        }
    }
}
