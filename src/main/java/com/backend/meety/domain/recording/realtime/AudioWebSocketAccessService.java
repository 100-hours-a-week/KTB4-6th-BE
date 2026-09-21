package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AudioFormat;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AudioWebSocketAccessService {

    private final RecordingSessionRepository recordingSessionRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Transactional(readOnly = true)
    public AudioWebSocketContext validate(Long userId, Long recordingSessionId, String audioFormat) {
        if (!AudioFormat.supports(audioFormat)) {
            throw new RecordingException(RecordingErrorCode.UNSUPPORTED_AUDIO_FORMAT);
        }
        RecordingSession session = recordingSessionRepository.findByIdAndDeletedAtIsNull(recordingSessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        if (session.getStatus() == RecordingSessionStatus.COMPLETED) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
        }
        if (session.getStatus() != RecordingSessionStatus.RECORDING
                && session.getStatus() != RecordingSessionStatus.PAUSED) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS);
        }
        TeamMember member = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        session.getMeeting().getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_OWNER_REQUIRED));
        if (!session.getStartedByTeamMember().getId().equals(member.getId())) {
            throw new RecordingException(RecordingErrorCode.RECORDING_OWNER_REQUIRED);
        }
        return new AudioWebSocketContext(userId, session.getMeeting().getId(), recordingSessionId, audioFormat);
    }
}
