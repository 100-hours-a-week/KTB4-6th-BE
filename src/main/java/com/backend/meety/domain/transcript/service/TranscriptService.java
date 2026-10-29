package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.ai.realtime.AiSpeakerMappingMessage;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingDetailResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingParticipantResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingRequest;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingStateResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerResponse;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.event.TranscriptSpeakerMappingUpdatedEvent;
import com.backend.meety.domain.transcript.exception.TranscriptErrorCode;
import com.backend.meety.domain.transcript.exception.TranscriptException;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSpeakerRepository;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptService {

    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_KEYWORD_LENGTH = 20;

    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final TranscriptSpeakerRepository transcriptSpeakerRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingService meetingService;
    private final TeamMemberRepository teamMemberRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void saveFinalSegment(AiTranscriptSegmentMessage message) {
        String sourceSegmentKey = message.sourceSegmentKey();
        if (transcriptSegmentRepository.existsBySourceSegmentKey(sourceSegmentKey)) {
            log.debug("중복 transcript segment 저장을 건너뜁니다. meetingId={}, sourceSegmentKey={}",
                    message.meetingId(), sourceSegmentKey);
            return;
        }

        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(message.meetingId())
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TranscriptSpeaker transcriptSpeaker = null;
        if (message.payload().speakerId() != null) {
            RecordingSession recordingSession = recordingSessionRepository
                    .findByIdAndDeletedAtIsNull(message.recordingSessionId())
                    .filter(session -> session.getMeeting().getId().equals(meeting.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("transcript recording session does not match meeting"));
            transcriptSpeaker = findOrCreateAiSpeaker(meeting, recordingSession, message.payload().speakerId());
        }
        TranscriptSegment segment = TranscriptSegment.createFinal(
                meeting,
                sourceSegmentKey,
                message.payload().sequenceNumber(),
                message.payload().content(),
                message.payload().startedAtMs(),
                message.payload().endedAtMs(),
                message.payload().recognizedAt(),
                message.payload().speakerId()
        );
        if (transcriptSpeaker != null) { segment.assignSpeaker(transcriptSpeaker); }

        TranscriptSegment savedSegment;
        try {
            savedSegment = transcriptSegmentRepository.saveAndFlush(segment);
        } catch (DataIntegrityViolationException e) {
            log.debug("중복 transcript segment 저장을 건너뜁니다. meetingId={}, sourceSegmentKey={}",
                    message.meetingId(), sourceSegmentKey);
            return;
        }

        log.debug("TranscriptSegment 저장 완료. transcriptSegmentId={}, meetingId={}, sequenceNumber={}, sourceSegmentKey={}",
                savedSegment.getId(), savedSegment.getMeeting().getId(), savedSegment.getSequenceNumber(),
                savedSegment.getSourceSegmentKey());
        eventPublisher.publishEvent(new TranscriptCreatedEvent(
                savedSegment.getMeeting().getId(),
                savedSegment.getId(),
                savedSegment.getSequenceNumber(),
                savedSegment.getContent(),
                savedSegment.getStartedAtMs(),
                savedSegment.getEndedAtMs(),
                savedSegment.getRecognizedAt(),
                message.payload().speakerId(),
                transcriptSpeaker == null || transcriptSpeaker.getMappedTeamMember() == null
                        ? null : transcriptSpeaker.getMappedTeamMember().getId(),
                transcriptSpeaker == null ? null : transcriptSpeaker.displayName(),
                transcriptSpeaker == null ? null : transcriptSpeaker.getId()
        ));
        log.debug("TranscriptCreatedEvent 발행. transcriptSegmentId={}, meetingId={}",
                savedSegment.getId(), savedSegment.getMeeting().getId());
    }

    @Transactional
    public void applyAiSpeakerMapping(AiSpeakerMappingMessage message) {
        RecordingSession session = recordingSessionRepository.findByIdAndDeletedAtIsNull(message.recordingSessionId())
                .filter(value -> value.getMeeting().getId().equals(message.meetingId()))
                .orElseThrow(() -> new IllegalArgumentException("speaker mapping session does not match meeting"));
        Meeting meeting = session.getMeeting();
        java.util.Map<Long, TranscriptSpeaker> speakers = transcriptSpeakerRepository
                .findAllAiSpeakers(session.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(TranscriptSpeaker::getAiSpeakerId, value -> value));
        java.util.Map<Long, TeamMember> members = new java.util.HashMap<>();
        java.util.Set<Long> snapshotSpeakerIds = message.mappings().stream()
                .map(AiSpeakerMappingMessage.Mapping::speakerId).collect(java.util.stream.Collectors.toSet());
        for (AiSpeakerMappingMessage.Mapping mapping : message.mappings()) {
            if (mapping.teamMemberId() != null) {
                TeamMember member = teamMemberRepository.findById(mapping.teamMemberId())
                        .orElseThrow(() -> new IllegalArgumentException("speaker mapping team member does not exist"));
                if (meetingParticipantRepository.findByMeetingIdAndTeamMemberId(meeting.getId(), member.getId()).isEmpty()) {
                    throw new IllegalArgumentException("speaker mapping team member is not a meeting participant");
                }
                members.put(member.getId(), member);
            }
            TranscriptSpeaker speaker = speakers.computeIfAbsent(mapping.speakerId(), speakerId ->
                    TranscriptSpeaker.createAiSpeaker(meeting, session, speakerId,
                            com.backend.meety.domain.transcript.DiarizationPolicy.speakerLabel(speakerId)));
            speaker.applyAiMapping(mapping.teamMemberId() == null ? null : members.get(mapping.teamMemberId()));
        }
        speakers.values().stream().filter(TranscriptSpeaker::isAiMapped)
                .filter(speaker -> !snapshotSpeakerIds.contains(speaker.getAiSpeakerId()))
                .forEach(speaker -> speaker.applyAiMapping(null));
        transcriptSpeakerRepository.saveAllAndFlush(speakers.values());
        List<TranscriptSpeakerMappingUpdatedEvent.Mapping> updated = speakers.values().stream()
                .sorted(java.util.Comparator.comparing(TranscriptSpeaker::getAiSpeakerId)).map(speaker -> {
            Long teamMemberId = speaker.getMappedTeamMember() == null ? null : speaker.getMappedTeamMember().getId();
            return new TranscriptSpeakerMappingUpdatedEvent.Mapping(
                    speaker.getAiSpeakerId(), teamMemberId, speaker.displayName());
        }).toList();
        eventPublisher.publishEvent(new TranscriptSpeakerMappingUpdatedEvent(
                meeting.getId(), session.getId(), updated));
    }

    private TranscriptSpeaker findOrCreateAiSpeaker(Meeting meeting, RecordingSession session, Long speakerId) {
        return transcriptSpeakerRepository.findAiSpeaker(session.getId(), speakerId).orElseGet(() ->
                transcriptSpeakerRepository.saveAndFlush(TranscriptSpeaker.createAiSpeaker(
                        meeting, session, speakerId,
                        com.backend.meety.domain.transcript.DiarizationPolicy.speakerLabel(speakerId))));
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse getTranscripts(Long userId, Long meetingId) {
        return getTranscripts(userId, meetingId, null);
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse getTranscripts(Long userId, Long meetingId, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        meetingService.validateMeetingAccess(userId, meeting.getTeam().getId());
        return findTranscripts(meetingId, normalizedKeyword);
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse findTranscripts(Long meetingId, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        List<TranscriptSegment> segments = normalizedKeyword == null
                ? transcriptSegmentRepository.findAllByMeetingIdOrderBySequence(meetingId)
                : transcriptSegmentRepository.searchByMeetingIdAndContent(meetingId, normalizedKeyword);

        List<TranscriptSegmentResponse> responses = segments.stream()
                .map(TranscriptSegmentResponse::from)
                .toList();
        return new TranscriptSegmentListResponse(responses);
    }

    @Transactional
    public TranscriptSpeakerResponse updateSpeakerMapping(
            Long userId,
            Long meetingId,
            Long transcriptSpeakerId,
            TranscriptSpeakerMappingRequest request
    ) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        Long teamId = meeting.getTeam().getId();
        meetingService.validateMeetingAccess(userId, teamId);

        TranscriptSpeaker speaker = transcriptSpeakerRepository
                .findByIdAndMeetingIdAndDeletedAtIsNull(transcriptSpeakerId, meetingId)
                .orElseThrow(() -> new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND));

        String customAlias = normalizeCustomAlias(request.customAlias());
        if (request.teamMemberId() != null && customAlias != null) {
            throw new TranscriptException(TranscriptErrorCode.INVALID_SPEAKER_MAPPING);
        }

        if (request.teamMemberId() != null) {
            TeamMember teamMember = teamMemberRepository.findById(request.teamMemberId())
                    .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
            validateTeamMemberParticipatedInMeeting(meetingId, teamMember);
            speaker.mapToTeamMember(teamMember);
            return TranscriptSpeakerResponse.from(speaker);
        }

        if (customAlias != null) {
            speaker.mapToCustomAlias(customAlias);
            return TranscriptSpeakerResponse.from(speaker);
        }

        speaker.clearMapping();
        return TranscriptSpeakerResponse.from(speaker);
    }

    @Transactional(readOnly = true)
    public TranscriptSpeakerMappingDetailResponse getSpeakerMapping(
            Long userId,
            Long meetingId,
            Long segmentId
    ) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        meetingService.validateMeetingAccess(userId, meeting.getTeam().getId());

        TranscriptSegment segment = transcriptSegmentRepository.findByIdAndMeetingIdWithSpeaker(segmentId, meetingId)
                .orElseThrow(() -> new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SEGMENT_NOT_FOUND));
        TranscriptSpeaker speaker = segment.getTranscriptSpeaker();
        if (speaker == null) {
            throw new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND);
        }

        List<TranscriptSpeakerMappingParticipantResponse> participants = meetingParticipantRepository
                .findAllByMeetingIdWithTeamMember(meetingId)
                .stream()
                .map(TranscriptSpeakerMappingParticipantResponse::from)
                .toList();

        return new TranscriptSpeakerMappingDetailResponse(
                TranscriptSpeakerMappingStateResponse.from(speaker),
                participants
        );
    }

    private void validateTeamMemberParticipatedInMeeting(Long meetingId, TeamMember teamMember) {
        if (meetingParticipantRepository.findByMeetingIdAndTeamMemberId(meetingId, teamMember.getId()).isEmpty()) {
            throw new TranscriptException(TranscriptErrorCode.TEAM_MEMBER_NOT_MEETING_PARTICIPANT);
        }
    }

    private String normalizeCustomAlias(String customAlias) {
        if (customAlias == null) {
            return null;
        }
        String trimmedAlias = customAlias.trim();
        if (trimmedAlias.isBlank()) {
            throw new TranscriptException(TranscriptErrorCode.INVALID_SPEAKER_MAPPING);
        }
        return trimmedAlias;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmedKeyword = keyword.trim();
        if (trimmedKeyword.isBlank()) {
            return null;
        }
        if (trimmedKeyword.length() < MIN_KEYWORD_LENGTH || trimmedKeyword.length() > MAX_KEYWORD_LENGTH) {
            throw new TranscriptException(TranscriptErrorCode.INVALID_TRANSCRIPT_KEYWORD);
        }
        return trimmedKeyword;
    }
}
