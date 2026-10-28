package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.ChatPolicy;
import com.backend.meety.domain.ai.dto.InternalSummaryResponse;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.exception.AiChatErrorCode;
import com.backend.meety.domain.ai.exception.AiChatException;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.dto.MeetingListResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiInternalQueryService {

    private final AiRequestRepository aiRequestRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingService meetingService;
    private final TranscriptService transcriptService;
    private final MeetingSummaryService meetingSummaryService;
    private final RecordingSessionRepository recordingSessionRepository;

    @Transactional(readOnly = true)
    public MeetingListResponse getMeetings(Long aiRequestId, Long recordingSessionId, Long teamId, String keyword,
                                           LocalDate from, LocalDate to, String cursor) {
        Long accessibleTeamId = findAccessibleTeamId(aiRequestId, recordingSessionId);
        if (recordingSessionId != null && !teamId.equals(accessibleTeamId)) {
            throw new TeamException(TeamErrorCode.TEAM_ACCESS_DENIED);
        }
        return meetingService.findMeetings(accessibleTeamId, keyword, from, to, cursor);
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse getTranscripts(Long aiRequestId, Long recordingSessionId, Long meetingId,
                                                        String keyword) {
        validateTeamMeeting(findAccessibleTeamId(aiRequestId, recordingSessionId), meetingId);
        return transcriptService.findTranscripts(meetingId, keyword);
    }

    @Transactional(readOnly = true)
    public InternalSummaryResponse getLatestSummary(Long aiRequestId, Long recordingSessionId, Long meetingId) {
        validateTeamMeeting(findAccessibleTeamId(aiRequestId, recordingSessionId), meetingId);
        return new InternalSummaryResponse(meetingSummaryService.findLatestCompletedSummary(meetingId).orElse(null));
    }

    private Long findAccessibleTeamId(Long aiRequestId, Long recordingSessionId) {
        if ((aiRequestId == null) == (recordingSessionId == null)) {
            throw new AiChatException(AiChatErrorCode.INTERNAL_QUERY_IDENTIFIER_INVALID);
        }
        return aiRequestId != null
                ? findProcessingChatTeamId(aiRequestId)
                : findActiveRecordingTeamId(recordingSessionId);
    }

    private Long findProcessingChatTeamId(Long aiRequestId) {
        return aiRequestRepository.findById(aiRequestId)
                .filter(request -> request.getRequestType() == AiRequestType.CHAT)
                .filter(request -> ChatPolicy.PROCESSING_STATUSES.contains(request.getStatus()))
                .map(AiRequest::getTeam)
                .map(Team::getId)
                .orElseThrow(() -> new AiChatException(AiChatErrorCode.AI_REQUEST_ACCESS_DENIED));
    }

    private Long findActiveRecordingTeamId(Long recordingSessionId) {
        return recordingSessionRepository.findByIdWithMeetingTeamAndStarter(recordingSessionId)
                .filter(session -> session.getStatus() == RecordingSessionStatus.RECORDING
                        || session.getStatus() == RecordingSessionStatus.PAUSED)
                .map(session -> session.getMeeting().getTeam().getId())
                .orElseThrow(() -> new AiChatException(AiChatErrorCode.RECORDING_SESSION_ACCESS_DENIED));
    }

    private void validateTeamMeeting(Long teamId, Long meetingId) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        if (!meeting.getTeam().getId().equals(teamId)) {
            throw new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED);
        }
    }
}
