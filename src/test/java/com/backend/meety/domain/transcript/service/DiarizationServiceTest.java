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
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
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
        first = withId(TranscriptSegment.createFinal(meeting, "k-1", 0L, "첫 발화", 0L, 1000L, NOW), 101L);
        second = withId(TranscriptSegment.createFinal(meeting, "k-2", 1L, "둘째 발화", 1000L, null, NOW), 102L);
        when(aiRequests.findById(AI_REQUEST_ID)).thenReturn(Optional.of(aiRequest));
        when(meetings.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(sessions.findByIdAndDeletedAtIsNull(SESSION_ID)).thenReturn(Optional.of(session));
        when(segments.findAllByMeetingIdOrderBySequence(MEETING_ID)).thenReturn(List.of(first, second));
        when(storage.createDownloadUrl(anyString(), any(Duration.class)))
                .thenReturn(URI.create("https://bucket.s3.amazonaws.com/meetings/100.mp4?sig=1").toURL());
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
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(availableAudio()));

        Optional<DiarizationAiRequest> request = service.startProcessing(AI_REQUEST_ID);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request).isPresent();
        assertThat(request.get().requestId()).isEqualTo("900");
        assertThat(request.get().meetingId()).isEqualTo(MEETING_ID);
        assertThat(request.get().audioUrl()).startsWith("https://");
        assertThat(request.get().segments()).hasSize(2);
        assertThat(request.get().segments().get(0).segmentId()).isEqualTo(101L);
        assertThat(request.get().segments().get(1).endedAtMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("오디오가 아직 없으면 ACCEPTED로 두고 기다린다")
    void startProcessingWaitsForAudio() {
        ReflectionTestUtils.setField(aiRequest, "createdAt", NOW.minusMinutes(1));
        when(audioFiles.findByMeetingIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        assertThat(service.startProcessing(AI_REQUEST_ID)).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        verify(storage, never()).createDownloadUrl(anyString(), any());
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

        service.completeProcessing(AI_REQUEST_ID, response);

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

        service.completeProcessing(AI_REQUEST_ID, response);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(first.getTranscriptSpeaker()).isNull();
        assertThat(second.getTranscriptSpeaker()).isNull();
    }

    @Test
    @DisplayName("응답의 meetingId가 다르면 실패 처리한다")
    void completeProcessingFailsOnMeetingMismatch() {
        aiRequest.markProcessing();

        service.completeProcessing(AI_REQUEST_ID, new DiarizationAiResponse(MEETING_ID + 1, List.of()));

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        verify(speakers, never()).saveAll(any());
    }

    @Test
    @DisplayName("재시도 한도 전에는 ACCEPTED로 되돌린다")
    void retryableFailureRequeuesBelowLimit() {
        aiRequest.markProcessing();

        service.handleRetryableFailure(AI_REQUEST_ID);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(aiRequest.getRetryCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("재시도 한도에 도달하면 최종 실패한다")
    void retryableFailureFailsAtLimit() {
        aiRequest.markProcessing();
        aiRequest.increaseRetryCount();
        aiRequest.increaseRetryCount();

        service.handleRetryableFailure(AI_REQUEST_ID);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }
}
