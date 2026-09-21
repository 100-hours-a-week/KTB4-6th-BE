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
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.dto.RecordingSessionResponse;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.event.RecordingLifecycleEvent;
import com.backend.meety.domain.recording.event.RecordingLifecycleEventType;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

    private static final long RECORDING_CREDIT_COST = 20L;
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

    @Transactional
    public RecordingSessionResponse start(Long userId, Long meetingId) {
        Meeting meeting = lockMeeting(meetingId);
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

        TeamCredit credit = creditRepository.findByTeamIdForUpdate(meeting.getTeam().getId())
                .orElseThrow(() -> {
                    log.error("녹음 시작에 필요한 팀 크레딧 행이 없습니다. teamId={}", meeting.getTeam().getId());
                    return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                });
        credit.validateCanUse(RECORDING_CREDIT_COST);
        LocalDateTime now = LocalDateTime.now(clock);
        RecordingSession session = recordingRepository.save(RecordingSession.start(meeting, member, now));
        credit.use(RECORDING_CREDIT_COST);
        ledgerRepository.save(CreditLedger.useForRecording(
                meeting.getTeam(), session.getId(), RECORDING_CREDIT_COST, credit.getBalance()));
        meeting.start(now);
        RecordingSessionResponse response = RecordingSessionResponse.from(session);
        eventPublisher.publishEvent(new RecordingLifecycleEvent(
                RecordingLifecycleEventType.STARTED,
                response.meetingId(),
                response.recordingSessionId()
        ));
        return response;
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
        validateRequestedStatus(status);
        Long meetingId = recordingRepository.findMeetingIdByIdAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        Meeting meeting = lockMeeting(meetingId);
        RecordingSession session = recordingRepository.findByIdForUpdateAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        validateOwner(userId, meeting, session);
        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        switch (status) {
            case PAUSED -> session.pause(now);
            case RECORDING -> session.resume(now);
            case COMPLETED -> {
                session.complete(now);
                meeting.complete(now);
            }
            default -> throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS);
        }
        RecordingSessionResponse response = RecordingSessionResponse.from(session);
        eventPublisher.publishEvent(new RecordingLifecycleEvent(
                toLifecycleType(status),
                response.meetingId(),
                response.recordingSessionId()
        ));
        return response;
    }

    @Transactional
    public void completeByTimeout(Long sessionId) {
        Long meetingId = recordingRepository.findMeetingIdByIdAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        Meeting meeting = lockMeeting(meetingId);
        RecordingSession session = recordingRepository.findByIdForUpdateAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS
                || (session.getStatus() != RecordingSessionStatus.RECORDING
                && session.getStatus() != RecordingSessionStatus.PAUSED)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        session.complete(now);
        meeting.complete(now);
        eventPublisher.publishEvent(new RecordingLifecycleEvent(
                RecordingLifecycleEventType.COMPLETED,
                meeting.getId(),
                session.getId()
        ));
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

    private RecordingLifecycleEventType toLifecycleType(RecordingSessionStatus status) {
        return switch (status) {
            case RECORDING -> RecordingLifecycleEventType.RESUMED;
            case PAUSED -> RecordingLifecycleEventType.PAUSED;
            case COMPLETED -> RecordingLifecycleEventType.COMPLETED;
            default -> throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS);
        };
    }
}
