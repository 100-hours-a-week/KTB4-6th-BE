package com.backend.meety.domain.transcript.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.session;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.DiarizationAiRequest;
import com.backend.meety.domain.ai.client.DiarizationAiResponse;
import com.backend.meety.domain.ai.client.DiarizationAiResultSegment;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.ai.service.AiProcessingAttempt;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.DiarizationPolicy;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSpeakerRepository;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DiarizationServiceTest {

    private static final long MEETING_ID = 100L;
    private static final long SESSION_ID = 700L;
    private static final long AI_REQUEST_ID = 900L;
    private static final String KEY = "DIARIZATION:MEETING:" + MEETING_ID;

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final RecordingSessionRepository sessions = mock(RecordingSessionRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final TranscriptSpeakerRepository speakers = mock(TranscriptSpeakerRepository.class);
    private final AudioFileRepository audioFiles = mock(AudioFileRepository.class);
    private final AudioFileStorage storage = mock(AudioFileStorage.class);
    private final DiarizationService service = new DiarizationService(
            aiRequests, meetings, sessions, segments, speakers, audioFiles, storage, CLOCK);

    private Team team;
    private TeamMember member;
    private Meeting meeting;
    private RecordingSession session;
    private AiRequest aiRequest;
    private TranscriptSegment first;
    private TranscriptSegment second;

    @BeforeEach
    void setUp() throws Exception {
        team = team();
        member = member(team);
        meeting = meeting(team, member);
        session = session(meeting, member);
        aiRequest = withId(AiRequest.create(team, member, KEY, AiRequestType.DIARIZATION), AI_REQUEST_ID);
        first = withId(TranscriptSegment.createFinal(meeting, "k-1", 0L, "첫 발화", 0L, 1000L, NOW, null), 101L);
        second = withId(TranscriptSegment.createFinal(meeting, "k-2", 1L, "둘째 발화", 1000L, null, NOW, null), 102L);
        when(aiRequests.claimAccepted(eq(AI_REQUEST_ID), anyString())).thenAnswer(invocation -> {
            if (!aiRequest.isAccepted()) {
                return 0;
            }
            aiRequest.markProcessing();
            return 1;
        });
        when(aiRequests.findById(AI_REQUEST_ID)).thenReturn(Optional.of(aiRequest));
        when(meetings.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(sessions.findByIdAndDeletedAtIsNull(SESSION_ID)).thenReturn(Optional.of(session));
        when(segments.findAllByMeetingIdOrderBySequence(MEETING_ID)).thenReturn(List.of(first, second));
        when(storage.createDownloadUrl(anyString(), any(Duration.class)))
                .thenReturn(URI.create("https://bucket.s3.amazonaws.com/meetings/100.mp4?sig=1").toURL());
        when(aiRequests.completeProcessing(AI_REQUEST_ID, "token-a")).thenAnswer(invocation -> {
            aiRequest.markCompleted();
            return 1;
        });
        when(aiRequests.retryProcessing(AI_REQUEST_ID, "token-a", DiarizationPolicy.MAX_RETRY_COUNT - 1))
                .thenAnswer(invocation -> {
                    if (aiRequest.getRetryCount() >= DiarizationPolicy.MAX_RETRY_COUNT - 1) {
                        return 0;
                    }
                    aiRequest.increaseRetryCount();
                    aiRequest.markAccepted();
                    return 1;
                });
        when(aiRequests.failRetryExhaustedProcessing(
                AI_REQUEST_ID, "token-a", DiarizationPolicy.MAX_RETRY_COUNT - 1,
                DiarizationPolicy.MAX_RETRY_COUNT)).thenAnswer(invocation -> {
                    if (aiRequest.getRetryCount() < DiarizationPolicy.MAX_RETRY_COUNT - 1) {
                        return 0;
                    }
                    if (aiRequest.getRetryCount() < DiarizationPolicy.MAX_RETRY_COUNT) {
                        aiRequest.increaseRetryCount();
                    }
                    aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
                    return 1;
                });
        when(aiRequests.failProcessing(eq(AI_REQUEST_ID), anyString())).thenAnswer(invocation -> {
            aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            return 1;
        });
    }

    private AudioFile availableAudio() {
        AudioFile audio = withId(AudioFile.create(session, "meetings/100.mp4", "audio/mp4"), 300L);
        audio.markAvailable(10L, 5000L, NOW, NOW.plusDays(1));
        return audio;
    }

    @Test
    @DisplayName("전사가 있으면 ACCEPTED DIARIZATION 요청을 등록한다")
    void registersAcceptedRequest() {
        when(aiRequests.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(segments.existsByMeetingId(MEETING_ID)).thenReturn(true);

        service.register(MEETING_ID, SESSION_ID);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiRequests).save(captor.capture());
        assertThat(captor.getValue().getRequestType()).isEqualTo(AiRequestType.DIARIZATION);
        assertThat(captor.getValue().getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getTeamMember()).isSameAs(member);
    }

    @Test
    @DisplayName("전사가 없으면 TRANSCRIPT_EMPTY 실패로 등록한다")
    void registersFailedWhenTranscriptEmpty() {
        when(aiRequests.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(segments.existsByMeetingId(MEETING_ID)).thenReturn(false);

        service.register(MEETING_ID, SESSION_ID);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiRequests).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(captor.getValue().getFailureReason()).isEqualTo(AiFailureReason.TRANSCRIPT_EMPTY);
    }

    @Test
    @DisplayName("이미 등록된 회의는 다시 등록하지 않는다")
    void skipsDuplicateRegistration() {
        when(aiRequests.findByIdempotencyKey(KEY)).thenReturn(Optional.of(aiRequest));

        service.register(MEETING_ID, SESSION_ID);

        verify(aiRequests, never()).save(any());
    }

    @Test
    @DisplayName("오디오가 준비되면 PROCESSING으로 전이하고 presigned URL과 전사 구간으로 요청을 만든다")
    void startProcessingBuildsRequestWhenAudioAvailable() {
        // 테스트 목적:
        // 오디오와 전사가 모두 준비된 화자 분리 요청이 원자적 선점에 성공하면
        // presigned URL과 전사 구간을 포함한 AI 요청이 반환되는지 검증한다.

        // given
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(availableAudio()));

        // when
        Optional<AiProcessingAttempt<DiarizationAiRequest>> request = service.startProcessing(AI_REQUEST_ID);

        // then
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request).isPresent();
        assertThat(request.get().processingToken()).isNotBlank();
        assertThat(request.get().payload().requestId()).isEqualTo("900");
        assertThat(request.get().payload().meetingId()).isEqualTo(MEETING_ID);
        assertThat(request.get().payload().audioUrl()).startsWith("https://");
        assertThat(request.get().payload().segments()).hasSize(2);
        assertThat(request.get().payload().segments().get(0).segmentId()).isEqualTo(101L);
        assertThat(request.get().payload().segments().get(1).endedAtMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("오디오가 아직 없으면 ACCEPTED로 두고 기다린다")
    void startProcessingWaitsForAudio() {
        // 테스트 목적:
        // 오디오 대기 한도 안에서 파일이 준비되지 않은 요청은 선점하지 않고
        // ACCEPTED 상태로 남겨 다음 폴링에서 다시 확인하는지 검증한다.

        // given
        ReflectionTestUtils.setField(aiRequest, "createdAt", NOW.minusMinutes(1));
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        // when
        Optional<AiProcessingAttempt<DiarizationAiRequest>> result = service.startProcessing(AI_REQUEST_ID);

        // then
        assertThat(result).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        verify(aiRequests, never()).claimAccepted(anyLong(), anyString());
        verify(storage, never()).createDownloadUrl(anyString(), any());
    }

    @Test
    @DisplayName("전사가 없으면 선점하지 않고 TRANSCRIPT_EMPTY로 실패 처리한다")
    void startProcessingFailsWithoutTranscriptBeforeClaim() {
        // 테스트 목적:
        // 오디오는 준비됐지만 전사 구간이 없는 요청은 원자적 선점을 시도하지 않고
        // 기존 정책대로 TRANSCRIPT_EMPTY 실패로 종료하는지 검증한다.

        // given
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(availableAudio()));
        when(segments.findAllByMeetingIdOrderBySequence(MEETING_ID)).thenReturn(List.of());

        // when
        Optional<AiProcessingAttempt<DiarizationAiRequest>> result = service.startProcessing(AI_REQUEST_ID);

        // then
        assertThat(result).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.TRANSCRIPT_EMPTY);
        verify(aiRequests, never()).claimAccepted(anyLong(), anyString());
        verify(storage, never()).createDownloadUrl(anyString(), any());
    }

    @Test
    @DisplayName("presigned URL 생성 후 선점에 실패하면 AI 요청을 반환하지 않는다")
    void startProcessingSkipsWhenClaimMissesAfterPreparation() {
        // 테스트 목적:
        // 두 인스턴스가 화자 분리 준비 조건을 동시에 통과하더라도
        // 마지막 조건부 UPDATE에서 실패한 인스턴스는 AI 호출 대상으로 넘기지 않는지 검증한다.

        // given
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(availableAudio()));
        doReturn(0).when(aiRequests).claimAccepted(eq(AI_REQUEST_ID), anyString());

        // when
        Optional<AiProcessingAttempt<DiarizationAiRequest>> result = service.startProcessing(AI_REQUEST_ID);

        // then
        assertThat(result).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        verify(storage).createDownloadUrl(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("오디오 대기 한도를 넘기면 AUDIO_FILE_MISSING으로 실패 처리한다")
    void startProcessingFailsWhenAudioWaitExceeded() {
        ReflectionTestUtils.setField(aiRequest, "createdAt", NOW.minusMinutes(11));
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        assertThat(service.startProcessing(AI_REQUEST_ID)).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AUDIO_FILE_MISSING);
    }

    @Test
    @DisplayName("ACCEPTED가 아니면 건너뛴다")
    void startProcessingSkipsNonAccepted() {
        aiRequest.markProcessing();

        assertThat(service.startProcessing(AI_REQUEST_ID)).isEmpty();
        verify(audioFiles, never()).findByMeetingIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    @DisplayName("응답의 speakerId마다 화자를 만들고 전사 구간에 연결한다")
    void completeProcessingAssignsSpeakers() {
        aiRequest.markProcessing();
        DiarizationAiResponse response = new DiarizationAiResponse(MEETING_ID, List.of(
                new DiarizationAiResultSegment(101L, "첫 발화", 0L, 1000L, 1L),
                new DiarizationAiResultSegment(102L, "둘째 발화", 1000L, 1000L, 0L)));

        service.completeProcessing(AI_REQUEST_ID, "token-a", response);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(first.getTranscriptSpeaker().getSpeakerLabel()).isEqualTo("화자 2");
        assertThat(second.getTranscriptSpeaker().getSpeakerLabel()).isEqualTo("화자 1");
        assertThat(first.getTranscriptSpeaker().getMeeting()).isSameAs(meeting);
        ArgumentCaptor<Iterable<TranscriptSpeaker>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(speakers).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("speakerId가 null이거나 모르는 구간은 화자를 비워 둔다")
    void completeProcessingLeavesUnknownSegmentsUnassigned() {
        aiRequest.markProcessing();
        DiarizationAiResponse response = new DiarizationAiResponse(MEETING_ID, List.of(
                new DiarizationAiResultSegment(101L, "첫 발화", 0L, 1000L, null),
                new DiarizationAiResultSegment(999L, "없는 구간", 0L, 1L, 0L)));

        service.completeProcessing(AI_REQUEST_ID, "token-a", response);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(first.getTranscriptSpeaker()).isNull();
        assertThat(second.getTranscriptSpeaker()).isNull();
    }

    @Test
    @DisplayName("응답의 meetingId가 다르면 실패 처리한다")
    void completeProcessingFailsOnMeetingMismatch() {
        aiRequest.markProcessing();

        service.completeProcessing(
                AI_REQUEST_ID, "token-a", new DiarizationAiResponse(MEETING_ID + 1, List.of()));

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        verify(speakers, never()).saveAll(any());
    }

    @Test
    @DisplayName("재시도 한도 전에는 ACCEPTED로 되돌린다")
    void retryableFailureRequeuesBelowLimit() {
        aiRequest.markProcessing();

        service.handleRetryableFailure(AI_REQUEST_ID, "token-a");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(aiRequest.getRetryCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("재시도 한도에 도달하면 최종 실패한다")
    void retryableFailureFailsAtLimit() {
        aiRequest.markProcessing();
        aiRequest.increaseRetryCount();
        aiRequest.increaseRetryCount();

        service.handleRetryableFailure(AI_REQUEST_ID, "token-a");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }

    @Test
    @DisplayName("이전 Attempt의 늦은 화자 분리 성공 응답은 최신 화자 매핑을 덮어쓰지 않는다")
    void lateSuccessDoesNotOverwriteCurrentSpeakerMapping() {
        // 테스트 목적:
        // 재시도 Attempt가 반영한 화자 매핑이 있는 상태에서 이전 token의 성공 응답이 도착해도
        // 기존 TranscriptSegment 매핑과 화자 결과가 유지되는지 검증한다.

        // given
        aiRequest.markProcessing();
        TranscriptSpeaker currentSpeaker = TranscriptSpeaker.create(meeting, "최신 화자");
        first.assignSpeaker(currentSpeaker);
        when(aiRequests.completeProcessing(AI_REQUEST_ID, "old-token")).thenReturn(0);
        DiarizationAiResponse oldResponse = new DiarizationAiResponse(MEETING_ID, List.of(
                new DiarizationAiResultSegment(101L, "첫 발화", 0L, 1000L, 9L)));

        // when
        service.completeProcessing(AI_REQUEST_ID, "old-token", oldResponse);

        // then
        assertThat(first.getTranscriptSpeaker()).isSameAs(currentSpeaker);
        assertThat(first.getTranscriptSpeaker().getSpeakerLabel()).isEqualTo("최신 화자");
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        verify(speakers, never()).saveAll(any());
    }

    @Test
    @DisplayName("이전 Attempt의 늦은 화자 분리 실패 응답은 최신 Attempt를 변경하지 않는다")
    void lateFailureDoesNotChangeCurrentDiarizationAttempt() {
        // 테스트 목적:
        // 최신 화자 분리 Attempt가 PROCESSING인 동안 이전 token의 실패 응답이 도착해도
        // 상태와 retryCount가 유지되는지 검증한다.

        // given
        aiRequest.markProcessing();

        // when
        service.handleRetryableFailure(AI_REQUEST_ID, "old-token");

        // then
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(aiRequest.getRetryCount()).isZero();
    }
}
