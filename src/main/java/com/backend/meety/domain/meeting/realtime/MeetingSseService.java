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
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingSseService {

    private static final String CONNECTED_EVENT_NAME = "CONNECTED";

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingSseRegistry registry;
    private final MeetingSseEmitterFactory emitterFactory;

    @Transactional(readOnly = true)
    public SseEmitter connect(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TeamMember teamMember = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
        if (!meetingParticipantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                meetingId, teamMember.getId(), ParticipationStatus.JOINED)) {
            throw new MeetingException(MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);
        }

        SseEmitter emitter = emitterFactory.create();
        registerLifecycleCallbacks(meetingId, userId, emitter);
        registry.register(meetingId, userId, emitter)
                .ifPresent(SseEmitter::complete);
        try {
            sendConnectedEvent(meetingId, emitter);
        } catch (IOException e) {
            registry.remove(meetingId, userId, emitter);
            emitter.completeWithError(e);
            log.warn("SSE 최초 연결 이벤트 전송에 실패했습니다. meetingId={}, userId={}", meetingId, userId, e);
            throw new IllegalStateException("SSE initial event send failed", e);
        }
        return emitter;
    }

    private void registerLifecycleCallbacks(Long meetingId, Long userId, SseEmitter emitter) {
        emitter.onCompletion(() -> registry.remove(meetingId, userId, emitter));
        emitter.onTimeout(() -> registry.remove(meetingId, userId, emitter));
        emitter.onError(ignored -> registry.remove(meetingId, userId, emitter));
    }

    private void sendConnectedEvent(Long meetingId, SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event()
                .name(CONNECTED_EVENT_NAME)
                .data(MeetingSseConnectedEvent.connected(meetingId)));
    }
}
