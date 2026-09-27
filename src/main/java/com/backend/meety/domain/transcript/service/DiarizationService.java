package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.client.DiarizationAiRequest;
import com.backend.meety.domain.ai.client.DiarizationAiResponse;
import com.backend.meety.domain.ai.client.DiarizationAiResultSegment;
import com.backend.meety.domain.ai.client.DiarizationAiSegment;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.DiarizationPolicy;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSpeakerRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiarizationService {

    private final AiRequestRepository aiRequestRepository;
    private final MeetingRepository meetingRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final TranscriptSpeakerRepository transcriptSpeakerRepository;
    private final AudioFileRepository audioFileRepository;
    private final AudioFileStorage audioFileStorage;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(Long meetingId, Long recordingSessionId) {
        String idempotencyKey = DiarizationPolicy.idempotencyKey(meetingId);
        if (aiRequestRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId).orElse(null);
        if (meeting == null) {
            log.warn("화자 분리를 등록할 회의가 없습니다. meetingId={}", meetingId);
            return;
        }
        TeamMember starter = recordingSessionRepository.findByIdAndDeletedAtIsNull(recordingSessionId)
                .map(RecordingSession::getStartedByTeamMember)
                .orElse(null);
        AiRequest aiRequest = AiRequest.create(meeting.getTeam(), starter, idempotencyKey, AiRequestType.DIARIZATION);
        if (!transcriptSegmentRepository.existsByMeetingId(meetingId)) {
            aiRequest.markFailed(AiFailureReason.TRANSCRIPT_EMPTY);
            log.warn("전사가 없어 화자 분리를 실패로 등록합니다. meetingId={}", meetingId);
        }
        aiRequestRepository.save(aiRequest);
    }

    /**
     * 오디오가 아직 AVAILABLE이 아니면 ACCEPTED로 두고 다음 폴링을 기다린다. 대기 한도를 넘기면 실패 처리한다.
     */
    @Transactional
    public Optional<DiarizationAiRequest> startProcessing(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElse(null);
        if (aiRequest == null || !aiRequest.isAccepted()) {
            return Optional.empty();
        }
        Long meetingId = DiarizationPolicy.meetingIdOf(aiRequest.getIdempotencyKey());
        AudioFile audioFile = audioFileRepository.findByMeetingIdAndDeletedAtIsNull(meetingId)
                .filter(AudioFile::isAvailable)
                .orElse(null);
        if (audioFile == null) {
            if (isAudioWaitExceeded(aiRequest)) {
                aiRequest.markFailed(AiFailureReason.AUDIO_FILE_MISSING);
                log.warn("오디오 파일이 올라오지 않아 화자 분리를 실패 처리합니다. aiRequestId={}, meetingId={}",
                        aiRequestId, meetingId);
            }
            return Optional.empty();
        }
        List<DiarizationAiSegment> segments = transcriptSegmentRepository
                .findAllByMeetingIdOrderBySequence(meetingId).stream()
                .map(this::toSegment)
                .toList();
        if (segments.isEmpty()) {
            aiRequest.markFailed(AiFailureReason.TRANSCRIPT_EMPTY);
            log.warn("전사가 없어 화자 분리를 실패 처리합니다. aiRequestId={}, meetingId={}", aiRequestId, meetingId);
            return Optional.empty();
        }
        String audioUrl;
        try {
            audioUrl = audioFileStorage
                    .createDownloadUrl(audioFile.getStorageKey(), AudioFilePolicy.DOWNLOAD_URL_VALIDITY)
                    .toString();
        } catch (Exception e) {
            log.error("화자 분리용 S3 presigned 다운로드 URL 발급에 실패했습니다. aiRequestId={}, audioFileId={}",
                    aiRequestId, audioFile.getId(), e);
            return Optional.empty();
        }
        aiRequest.markProcessing();
        return Optional.of(new DiarizationAiRequest(
                String.valueOf(aiRequest.getId()), meetingId, audioUrl, segments));
    }

    @Transactional
    public void completeProcessing(Long aiRequestId, DiarizationAiResponse response) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        Long meetingId = DiarizationPolicy.meetingIdOf(aiRequest.getIdempotencyKey());
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId).orElse(null);
        if (meeting == null || !meetingId.equals(response.meetingId())) {
            aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            log.error("화자 분리 응답이 회의와 일치하지 않습니다. aiRequestId={}, meetingId={}, responseMeetingId={}",
                    aiRequestId, meetingId, response.meetingId());
            return;
        }
        Map<Long, TranscriptSegment> segmentsById = transcriptSegmentRepository
                .findAllByMeetingIdOrderBySequence(meetingId).stream()
                .collect(Collectors.toMap(TranscriptSegment::getId, Function.identity()));
        Map<Long, TranscriptSpeaker> speakersBySpeakerId = new TreeMap<>();
        for (DiarizationAiResultSegment result : response.segments()) {
            if (result.speakerId() == null) {
                continue;
            }
            TranscriptSegment segment = segmentsById.get(result.segmentId());
            if (segment == null) {
                log.warn("화자 분리 응답의 전사 구간이 회의에 없습니다. aiRequestId={}, segmentId={}",
                        aiRequestId, result.segmentId());
                continue;
            }
            TranscriptSpeaker speaker = speakersBySpeakerId.computeIfAbsent(result.speakerId(),
                    speakerId -> TranscriptSpeaker.create(meeting, DiarizationPolicy.speakerLabel(speakerId)));
            segment.assignSpeaker(speaker);
        }
        transcriptSpeakerRepository.saveAll(speakersBySpeakerId.values());
        aiRequest.markCompleted();
        log.info("화자 분리가 완료되었습니다. aiRequestId={}, meetingId={}, speakerCount={}",
                aiRequestId, meetingId, speakersBySpeakerId.size());
    }

    @Transactional
    public void handleRetryableFailure(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        aiRequest.increaseRetryCount();
        if (aiRequest.getRetryCount() < DiarizationPolicy.MAX_RETRY_COUNT) {
            aiRequest.markAccepted();
            log.warn("AI 화자 분리 호출에 실패해 재시도합니다. aiRequestId={}, retryCount={}",
                    aiRequestId, aiRequest.getRetryCount());
            return;
        }
        failPermanently(aiRequest);
    }

    @Transactional
    public void handlePermanentFailure(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        failPermanently(aiRequest);
    }

    private void failPermanently(AiRequest aiRequest) {
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
        log.error("AI 화자 분리 호출이 최종 실패했습니다. aiRequestId={}, retryCount={}",
                aiRequest.getId(), aiRequest.getRetryCount());
    }

    private boolean isAudioWaitExceeded(AiRequest aiRequest) {
        LocalDateTime createdAt = aiRequest.getCreatedAt();
        return createdAt != null
                && createdAt.plus(DiarizationPolicy.AUDIO_WAIT_LIMIT).isBefore(LocalDateTime.now(clock));
    }

    private DiarizationAiSegment toSegment(TranscriptSegment segment) {
        Long endedAtMs = segment.getEndedAtMs() != null ? segment.getEndedAtMs() : segment.getStartedAtMs();
        return new DiarizationAiSegment(
                segment.getId(), segment.getContent(), segment.getStartedAtMs(), endedAtMs);
    }
}
