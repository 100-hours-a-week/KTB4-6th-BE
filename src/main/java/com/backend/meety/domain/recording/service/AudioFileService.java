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
import org.springframework.stereotype.Service;
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
        validateStarter(userId, session);
        logElapsed("[AUDIO_UPLOAD_URL] starter validation completed recordingSessionId={}, userId={}, teamId={}, "
                        + "elapsedMs={}",
                elapsedMs(segmentStartedAt), recordingSessionId, userId, session.getMeeting().getTeam().getId());

        // TODO: audio_files.recording_session_id에 UNIQUE가 없어 동시 요청 시 중복 발급이 가능하다. 스키마 변경 확정 후 제약을 추가한다.
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
