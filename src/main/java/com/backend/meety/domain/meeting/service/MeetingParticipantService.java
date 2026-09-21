package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.meeting.dto.MeetingParticipantListResponse;
import com.backend.meety.domain.meeting.dto.MeetingParticipantResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingParticipantService {

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public MeetingParticipantResponse joinMeeting(Long userId, Long meetingId) {
        Meeting meeting = findMeeting(meetingId);
        TeamMember teamMember = findActiveTeamMember(userId, meeting);
        validateParticipantChangeAllowed(meeting);

        MeetingParticipant participant = meetingParticipantRepository
                .findByMeetingIdAndTeamMemberId(meetingId, teamMember.getId())
                .map(this::joinExistingParticipant)
                .orElseGet(() -> meetingParticipantRepository.save(MeetingParticipant.create(meeting, teamMember)));

        return MeetingParticipantResponse.from(participant);
    }

    @Transactional(readOnly = true)
    public MeetingParticipantListResponse getParticipants(Long userId, Long meetingId) {
        Meeting meeting = findMeeting(meetingId);
        findActiveTeamMember(userId, meeting);

        List<MeetingParticipantResponse> participants = meetingParticipantRepository
                .findCurrentParticipantsByMeetingId(meetingId)
                .stream()
                .map(MeetingParticipantResponse::from)
                .toList();

        return new MeetingParticipantListResponse(
                participants.size(),
                participants
        );
    }

    @Transactional
    public void leaveMeeting(Long userId, Long meetingId) {
        Meeting meeting = findMeeting(meetingId);
        TeamMember teamMember = findActiveTeamMember(userId, meeting);
        validateLeaveAllowed(meeting);

        MeetingParticipant participant = meetingParticipantRepository
                .findByMeetingIdAndTeamMemberId(meetingId, teamMember.getId())
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.PARTICIPANT_NOT_FOUND));

        if (participant.isDisconnected()) {
            throw new MeetingException(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);
        }
        if (participant.isLeft()) {
            throw new MeetingException(MeetingErrorCode.ALREADY_LEFT);
        }
        if (!participant.isJoined()) {
            throw new MeetingException(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);
        }

        participant.leave(LocalDateTime.now(clock));
        eventPublisher.publishEvent(new MeetingParticipantLeftEvent(meetingId, userId));
    }

    private MeetingParticipant joinExistingParticipant(MeetingParticipant participant) {
        if (participant.isJoined()) {
            throw new MeetingException(MeetingErrorCode.ALREADY_PARTICIPATING);
        }
        if (participant.isDisconnected()) {
            throw new MeetingException(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);
        }
        if (!participant.isLeft()) {
            throw new MeetingException(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);
        }

        participant.rejoin();
        return participant;
    }

    private Meeting findMeeting(Long meetingId) {
        return meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
    }

    private TeamMember findActiveTeamMember(Long userId, Meeting meeting) {
        return teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(),
                        userId,
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
    }

    private void validateParticipantChangeAllowed(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.WAITING && meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new MeetingException(MeetingErrorCode.MEETING_PARTICIPATION_NOT_ALLOWED);
        }
    }

    private void validateLeaveAllowed(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.WAITING) {
            throw new MeetingException(MeetingErrorCode.MEETING_PARTICIPATION_NOT_ALLOWED);
        }
    }
}
