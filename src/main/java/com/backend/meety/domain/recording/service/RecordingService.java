package com.backend.meety.domain.recording.service;

import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.dto.RecordingSessionResponse;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import com.backend.meety.domain.recording.event.RecordingPausedEvent;
import com.backend.meety.domain.recording.event.RecordingResumedEvent;
import com.backend.meety.domain.recording.event.RecordingStartedEvent;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

    private static final long RECORDING_CREDIT_COST = 20L;
    private static final long SLOW_LOG_THRESHOLD_MS = 1_000L;
    private static final List<RecordingSessionStatus> ACTIVE_STATUSES = List.of(
            RecordingSessionStatus.RECORDING, RecordingSessionStatus.PAUSED
    );

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingParticipantRepository participantRepository;
    private final RecordingSessionRepository recordingRepository;
    private final TeamCreditRepository creditRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private DataSource dataSource;

    @Autowired(required = false)
    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional
    public RecordingSessionResponse start(Long userId, Long meetingId) {
        long totalStartedAt = System.nanoTime();
        log.info("[RECORDING_START] start meetingId={}, userId={}, thread={}",
                meetingId, userId, Thread.currentThread().getName());
        logHikari("RECORDING_START start");

        long segmentStartedAt = System.nanoTime();
        log.info("[RECORDING_START] meeting lock request meetingId={}, userId={}", meetingId, userId);
        Meeting meeting = lockMeeting(meetingId);
        logElapsed("[RECORDING_START] meeting lock acquired meetingId={}, userId={}, teamId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, meeting.getTeam().getId());

        validateStartAllowed(meeting);
        TeamMember member = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
        if (!participantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                meetingId, member.getId(), ParticipationStatus.JOINED)) {
            throw new MeetingException(MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);
        }
        if (recordingRepository.existsByMeetingIdAndStatusInAndDeletedAtIsNull(meetingId, ACTIVE_STATUSES)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_ACTIVE);
        }

        segmentStartedAt = System.nanoTime();
        log.info("[RECORDING_START] credit lock request meetingId={}, userId={}, teamId={}",
                meetingId, userId, meeting.getTeam().getId());
        TeamCredit credit = creditRepository.findByTeamIdForUpdate(meeting.getTeam().getId())
                .orElseThrow(() -> {
                    log.error("녹음 시작에 필요한 팀 크레딧 행이 없습니다. teamId={}", meeting.getTeam().getId());
                    return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                });
        logElapsed("[RECORDING_START] credit lock acquired meetingId={}, userId={}, teamId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, meeting.getTeam().getId());

        credit.validateCanUse(RECORDING_CREDIT_COST);
        LocalDateTime now = LocalDateTime.now(clock);
        segmentStartedAt = System.nanoTime();
        RecordingSession session = recordingRepository.save(RecordingSession.start(meeting, member, now));
        logElapsed("[RECORDING_START] recording session save completed meetingId={}, userId={}, "
                        + "recordingSessionId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, session.getId());

        segmentStartedAt = System.nanoTime();
        credit.use(RECORDING_CREDIT_COST);
        ledgerRepository.save(CreditLedger.useForRecording(
                meeting.getTeam(), session.getId(), RECORDING_CREDIT_COST, credit.getBalance()));
        logElapsed("[RECORDING_START] credit use and ledger save completed meetingId={}, userId={}, teamId={}, "
                        + "recordingSessionId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, meeting.getTeam().getId(), session.getId());

        segmentStartedAt = System.nanoTime();
        meeting.start(now);
        logElapsed("[RECORDING_START] meeting status changed meetingId={}, userId={}, recordingSessionId={}, "
                        + "elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, session.getId());

        eventPublisher.publishEvent(new RecordingStartedEvent(meeting.getId(), session.getId()));
        logHikari("RECORDING_START end");
        logElapsed("[RECORDING_START] end meetingId={}, userId={}, recordingSessionId={}, totalElapsedMs={}",
                elapsedMs(totalStartedAt), meetingId, userId, session.getId());
        return RecordingSessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public RecordingSessionResponse getActive(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        if (!teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)) {
            throw new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED);
        }
        return recordingRepository.findByMeetingIdAndStatusInAndDeletedAtIsNull(meetingId, ACTIVE_STATUSES)
                .map(RecordingSessionResponse::from)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
    }

    @Transactional
    public RecordingSessionResponse updateStatus(Long userId, Long sessionId, RecordingSessionStatus status) {
        long totalStartedAt = System.nanoTime();
        log.info("[RECORDING_STATUS] start recordingSessionId={}, userId={}, requestedStatus={}, thread={}",
                sessionId, userId, status, Thread.currentThread().getName());
        logHikari("RECORDING_STATUS start");

        validateRequestedStatus(status);
        Long meetingId = recordingRepository.findMeetingIdByIdAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        long segmentStartedAt = System.nanoTime();
        log.info("[RECORDING_STATUS] meeting lock request meetingId={}, recordingSessionId={}, userId={}",
                meetingId, sessionId, userId);
        Meeting meeting = lockMeeting(meetingId);
        logElapsed("[RECORDING_STATUS] meeting lock acquired meetingId={}, recordingSessionId={}, userId={}, "
                        + "teamId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, sessionId, userId, meeting.getTeam().getId());

        segmentStartedAt = System.nanoTime();
        log.info("[RECORDING_STATUS] recording session lock request meetingId={}, recordingSessionId={}, userId={}",
                meetingId, sessionId, userId);
        RecordingSession session = recordingRepository.findByIdForUpdateAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        logElapsed("[RECORDING_STATUS] recording session lock acquired meetingId={}, recordingSessionId={}, "
                        + "userId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, sessionId, userId);

        validateOwner(userId, meeting, session);
        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
        }

        segmentStartedAt = System.nanoTime();
        LocalDateTime now = LocalDateTime.now(clock);
        switch (status) {
            case PAUSED -> {
                session.pause(now);
                eventPublisher.publishEvent(new RecordingPausedEvent(meeting.getId(), session.getId()));
            }
            case RECORDING -> {
                session.resume(now);
                eventPublisher.publishEvent(new RecordingResumedEvent(meeting.getId(), session.getId()));
            }
            case COMPLETED -> {
                session.complete(now);
                meeting.complete(now);
                eventPublisher.publishEvent(new RecordingCompletedEvent(meeting.getId(), session.getId()));
                eventPublisher.publishEvent(new MeetingCompletedEvent(meeting.getId()));
            }
            default -> throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS);
        }
        logElapsed("[RECORDING_STATUS] status changed meetingId={}, recordingSessionId={}, userId={}, "
                        + "requestedStatus={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, sessionId, userId, status);
        logHikari("RECORDING_STATUS end");
        logElapsed("[RECORDING_STATUS] end meetingId={}, recordingSessionId={}, userId={}, requestedStatus={}, "
                        + "totalElapsedMs={}",
                elapsedMs(totalStartedAt), meetingId, sessionId, userId, status);
        return RecordingSessionResponse.from(session);
    }

    private Meeting lockMeeting(Long meetingId) {
        return meetingRepository.findByIdForUpdateAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
    }

    private void validateStartAllowed(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.COMPLETED) {
            throw new MeetingException(MeetingErrorCode.MEETING_COMPLETED);
        }
        if (meeting.getStatus() != MeetingStatus.WAITING) {
            throw new RecordingException(RecordingErrorCode.RECORDING_ALREADY_ACTIVE);
        }
    }

    private void validateOwner(Long userId, Meeting meeting, RecordingSession session) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_OWNER_REQUIRED));
        if (!session.getStartedByTeamMember().getId().equals(member.getId())) {
            throw new RecordingException(RecordingErrorCode.RECORDING_OWNER_REQUIRED);
        }
    }

    private void validateRequestedStatus(RecordingSessionStatus status) {
        if (status != RecordingSessionStatus.RECORDING && status != RecordingSessionStatus.PAUSED
                && status != RecordingSessionStatus.COMPLETED) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS);
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
