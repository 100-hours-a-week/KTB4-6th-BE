package com.backend.meety.domain.meeting.realtime;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingSseAccessService {

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;

    @Transactional(readOnly = true)
    public void validateCanConnect(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TeamMember teamMember = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
        if (!meetingParticipantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                meetingId, teamMember.getId(), ParticipationStatus.JOINED)) {
            throw new MeetingException(MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);
        }
    }
}
