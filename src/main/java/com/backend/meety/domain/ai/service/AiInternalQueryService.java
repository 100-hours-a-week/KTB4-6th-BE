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
import com.backend.meety.domain.team.entity.Team;
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

    @Transactional(readOnly = true)
    public MeetingListResponse getMeetings(Long aiRequestId, String keyword, LocalDate from, LocalDate to,
                                           String cursor) {
        Long teamId = findProcessingChatTeamId(aiRequestId);
        return meetingService.findMeetings(teamId, keyword, from, to, cursor);
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse getTranscripts(Long aiRequestId, Long meetingId, String keyword) {
        validateTeamMeeting(aiRequestId, meetingId);
        return transcriptService.findTranscripts(meetingId, keyword);
    }

    @Transactional(readOnly = true)
    public InternalSummaryResponse getLatestSummary(Long aiRequestId, Long meetingId) {
        validateTeamMeeting(aiRequestId, meetingId);
        return new InternalSummaryResponse(meetingSummaryService.findLatestCompletedSummary(meetingId).orElse(null));
    }

    private Long findProcessingChatTeamId(Long aiRequestId) {
        return aiRequestRepository.findById(aiRequestId)
                .filter(request -> request.getRequestType() == AiRequestType.CHAT)
                .filter(request -> ChatPolicy.PROCESSING_STATUSES.contains(request.getStatus()))
                .map(AiRequest::getTeam)
                .map(Team::getId)
                .orElseThrow(() -> new AiChatException(AiChatErrorCode.AI_REQUEST_ACCESS_DENIED));
    }

    private void validateTeamMeeting(Long aiRequestId, Long meetingId) {
        Long teamId = findProcessingChatTeamId(aiRequestId);
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        if (!meeting.getTeam().getId().equals(teamId)) {
            throw new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED);
        }
    }
}
