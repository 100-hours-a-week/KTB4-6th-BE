package com.backend.meety.domain.recording.service;

import com.backend.meety.domain.recording.dto.AudioFileDeleteResponse;
import com.backend.meety.domain.recording.dto.AudioFileDetailResponse;
import com.backend.meety.domain.recording.dto.AudioFileDownloadUrlResponse;
import com.backend.meety.domain.recording.dto.AudioFileResponse;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.entity.AudioFileStatus;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.exception.AudioFileException;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.event.AudioFileDeleteRequestedEvent;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.net.URL;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioFileService {

    private static final long SLOW_LOG_THRESHOLD_MS = 1_000L;

    private final RecordingSessionRepository recordingRepository;
    private final AudioFileRepository audioFileRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final AudioFileStorage audioFileStorage;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private DataSource dataSource;

    @Autowired(required = false)
    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional
    public AudioFileUploadUrlResponse createUploadUrl(Long userId, Long recordingSessionId, String contentType) {
        long totalStartedAt = System.nanoTime();
        log.info("[AUDIO_UPLOAD_URL] start recordingSessionId={}, userId={}, thread={}",
                recordingSessionId, userId, Thread.currentThread().getName());
        logHikari("AUDIO_UPLOAD_URL start");

        if (!AudioFilePolicy.isAllowedContentType(contentType)) {
            throw new AudioFileException(AudioFileErrorCode.INVALID_AUDIO_CONTENT_TYPE);
        }

        long segmentStartedAt = System.nanoTime();
        log.info("[AUDIO_UPLOAD_URL] recording session lookup start recordingSessionId={}, userId={}",
                recordingSessionId, userId);
        RecordingSession session = recordingRepository.findByIdAndDeletedAtIsNull(recordingSessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        logElapsed("[AUDIO_UPLOAD_URL] recording session lookup completed recordingSessionId={}, userId={}, "
                        + "meetingId={}, teamId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), recordingSessionId, userId,
                session.getMeeting().getId(), session.getMeeting().getTeam().getId());

        segmentStartedAt = System.nanoTime();
        log.info("[AUDIO_UPLOAD_URL] starter validation start recordingSessionId={}, userId={}, teamId={}",
                recordingSessionId, userId, session.getMeeting().getTeam().getId());
        if (!isStarter(userId, session)) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_CREATE_FORBIDDEN);
        }
        logElapsed("[AUDIO_UPLOAD_URL] starter validation completed recordingSessionId={}, userId={}, teamId={}, "
                        + "elapsedMs={}",
                elapsedMs(segmentStartedAt), recordingSessionId, userId, session.getMeeting().getTeam().getId());

        // TODO: audio_files.recording_session_id에 UNIQUE가 없어 동시 요청 시 중복 발급이 가능하다. 스키마 변경 확정 후 제약을 추가한다.
        // TODO: 업로드가 실패해 UPLOADING으로 남은 행도 중복으로 세어 재발급이 막힌다. 재발급 허용 정책 확정 후 조건을 조정한다.
        segmentStartedAt = System.nanoTime();
        log.info("[AUDIO_UPLOAD_URL] audio file existence check start recordingSessionId={}, userId={}",
                recordingSessionId, userId);
        if (audioFileRepository.existsByRecordingSessionIdAndDeletedAtIsNull(recordingSessionId)) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_ALREADY_EXISTS);
        }
        logElapsed("[AUDIO_UPLOAD_URL] audio file existence check completed recordingSessionId={}, userId={}, "
                        + "elapsedMs={}",
                elapsedMs(segmentStartedAt), recordingSessionId, userId);

        segmentStartedAt = System.nanoTime();
        log.info("[AUDIO_UPLOAD_URL] audio file save start recordingSessionId={}, userId={}",
                recordingSessionId, userId);
        AudioFile audioFile = audioFileRepository.save(
                AudioFile.create(session, storageKey(session), contentType));
        logElapsed("[AUDIO_UPLOAD_URL] audio file save completed recordingSessionId={}, userId={}, audioFileId={}, "
                        + "elapsedMs={}",
                elapsedMs(segmentStartedAt), recordingSessionId, userId, audioFile.getId());
        LocalDateTime now = LocalDateTime.now(clock);
        segmentStartedAt = System.nanoTime();
        log.info("[AUDIO_UPLOAD_URL] presign start recordingSessionId={}, userId={}, audioFileId={}",
                recordingSessionId, userId, audioFile.getId());
        String uploadUrl = uploadUrl(audioFile);
        logElapsed("[AUDIO_UPLOAD_URL] presign end recordingSessionId={}, userId={}, audioFileId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), recordingSessionId, userId, audioFile.getId());
        logHikari("AUDIO_UPLOAD_URL end");
        logElapsed("[AUDIO_UPLOAD_URL] end recordingSessionId={}, userId={}, totalElapsedMs={}",
                elapsedMs(totalStartedAt), recordingSessionId, userId);
        return new AudioFileUploadUrlResponse(
                audioFile.getId(),
                uploadUrl,
                now.plus(AudioFilePolicy.UPLOAD_URL_VALIDITY)
        );
    }

    @Transactional
    public AudioFileResponse completeUpload(
            Long userId, Long audioFileId, Long reportedFileSizeBytes, Long durationMs) {
        AudioFile audioFile = audioFileRepository.findByIdForUpdateAndDeletedAtIsNull(audioFileId)
                .orElseThrow(() -> new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_FOUND));
        if (!isStarter(userId, audioFile.getRecordingSession())) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_ACCESS_DENIED);
        }
        if (!audioFile.isUploading()) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_UPLOADING);
        }

        long fileSizeBytes = storedObjectSize(audioFile);
        if (!Long.valueOf(fileSizeBytes).equals(reportedFileSizeBytes)) {
            log.warn("업로드 완료 요청의 파일 크기가 S3 저장 크기와 다릅니다. audioFileId={}, reported={}, stored={}",
                    audioFileId, reportedFileSizeBytes, fileSizeBytes);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        audioFile.markAvailable(fileSizeBytes, durationMs, now, now.plus(AudioFilePolicy.RETENTION));
        return AudioFileResponse.from(audioFile);
    }

    @Transactional(readOnly = true)
    public AudioFileDetailResponse getByMeeting(Long userId, Long meetingId) {
        AudioFile audioFile = audioFileRepository.findByMeetingIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_FOUND));
        if (!isActiveTeamMember(userId, audioFile.getRecordingSession())) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_ACCESS_DENIED);
        }
        if (audioFile.getStatus() != AudioFileStatus.AVAILABLE) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_AVAILABLE);
        }
        return AudioFileDetailResponse.from(audioFile);
    }

    @Transactional(readOnly = true)
    public AudioFileDownloadUrlResponse createDownloadUrl(Long userId, Long audioFileId) {
        AudioFile audioFile = audioFileRepository.findByIdAndDeletedAtIsNull(audioFileId)
                .orElseThrow(() -> new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_FOUND));
        if (!isActiveTeamMember(userId, audioFile.getRecordingSession())) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_ACCESS_DENIED);
        }
        if (audioFile.getStatus() != AudioFileStatus.AVAILABLE) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_AVAILABLE);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (audioFile.isExpired(now)) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_EXPIRED);
        }
        return new AudioFileDownloadUrlResponse(
                downloadUrl(audioFile),
                now.plus(AudioFilePolicy.DOWNLOAD_URL_VALIDITY)
        );
    }

    @Transactional
    public AudioFileDeleteResponse requestDelete(Long userId, Long audioFileId) {
        AudioFile audioFile = audioFileRepository.findByIdForUpdate(audioFileId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_FOUND));
        if (!isTeamLeader(userId, audioFile.getRecordingSession())) {
            throw new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED);
        }
        if (audioFile.isDeleting()) {
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_DELETE_IN_PROGRESS);
        }

        audioFile.markDeletePending(LocalDateTime.now(clock));
        eventPublisher.publishEvent(
                new AudioFileDeleteRequestedEvent(audioFile.getId(), audioFile.getStorageKey()));
        return AudioFileDeleteResponse.from(audioFile);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeDelete(Long audioFileId, String storageKey) {
        try {
            audioFileStorage.deleteObject(storageKey);
        } catch (Exception e) {
            log.error("S3 객체 삭제에 실패했습니다. audioFileId={}", audioFileId, e);
            audioFileRepository.findById(audioFileId).ifPresent(AudioFile::markDeleteFailed);
            return;
        }
        audioFileRepository.findById(audioFileId).ifPresent(AudioFile::markDeleted);
    }

    private boolean isTeamLeader(Long userId, RecordingSession session) {
        return teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        session.getMeeting().getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .filter(TeamMember::isLeader)
                .isPresent();
    }

    private String downloadUrl(AudioFile audioFile) {
        try {
            return audioFileStorage
                    .createDownloadUrl(audioFile.getStorageKey(), AudioFilePolicy.DOWNLOAD_URL_VALIDITY)
                    .toString();
        } catch (Exception e) {
            log.error("S3 presigned 다운로드 URL 발급에 실패했습니다. audioFileId={}", audioFile.getId(), e);
            throw new AudioFileException(AudioFileErrorCode.AUDIO_DOWNLOAD_URL_CREATE_FAILED);
        }
    }

    private long storedObjectSize(AudioFile audioFile) {
        try {
            return audioFileStorage.findObjectSize(audioFile.getStorageKey())
                    .orElseThrow(() -> new AudioFileException(AudioFileErrorCode.AUDIO_OBJECT_NOT_FOUND));
        } catch (AudioFileException e) {
            throw e;
        } catch (Exception e) {
            log.error("S3 객체 조회에 실패했습니다. audioFileId={}", audioFile.getId(), e);
            throw new AudioFileException(AudioFileErrorCode.AUDIO_FILE_UPDATE_FAILED);
        }
    }

    private boolean isActiveTeamMember(Long userId, RecordingSession session) {
        return teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                session.getMeeting().getTeam().getId(), userId, MembershipStatus.ACTIVE);
    }

    private boolean isStarter(Long userId, RecordingSession session) {
        return teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        session.getMeeting().getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .filter(member -> session.getStartedByTeamMember().getId().equals(member.getId()))
                .isPresent();
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

    private long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void logElapsed(String message, long elapsedMs, Object... args) {
        Object[] values = Arrays.copyOf(args, args.length + 1);
        values[args.length] = elapsedMs;
        if (elapsedMs >= SLOW_LOG_THRESHOLD_MS) {
            log.warn(message, values);
            return;
        }
        log.info(message, values);
    }

    private void logHikari(String context) {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return;
        }
        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        if (pool == null) {
            return;
        }
        log.info("[HIKARI] context={} active={} idle={} total={} pending={}",
                context,
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection());
    }
}
