package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import com.backend.meety.domain.ai.event.AiRecoverableFailureEvent;
import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.owner.RecordingOwnerService;
import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveProperties;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiLiveMeetingConnectionServiceTest {

    private final List<Object> readyEvents = new ArrayList<>();
    private final AtomicBoolean readyResult = new AtomicBoolean();
    private final AiLiveMeetingConnectionRegistry registry = new AiLiveMeetingConnectionRegistry();
    private final TestAiWebSocketClient client = new TestAiWebSocketClient();
    private final TestAiStopTimeoutScheduler timeoutScheduler = new TestAiStopTimeoutScheduler();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final RecordingOwnerService ownerService = mock(RecordingOwnerService.class);
    private final MeetingRepository meetingRepository = mock(MeetingRepository.class);
    private final MeetingParticipantRepository meetingParticipantRepository = mock(MeetingParticipantRepository.class);
    private final AiLiveMeetingConnectionService service = new AiLiveMeetingConnectionService(
            registry,
            client,
            new AiLiveMeetingProperties(URI.create("ws://localhost:8000/v1/live-meeting")),
            new FixedAiRequestIdGenerator(),
            timeoutScheduler,
            objectMapper,
            readyEvents::add,
            new TranscriptSaveDispatcher(Runnable::run,
                    new TranscriptSaveProperties(500, 5000, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ZERO),
                    transcriptService),
            ownerService,
            meetingRepository,
            meetingParticipantRepository,
            new AiAudioAckService(registry, new com.backend.meety.domain.recording.realtime.AudioWebSocketRegistry(),
                    mock(com.backend.meety.domain.recording.realtime.AudioSequenceRepository.class),
                    new AiAudioAckProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024),
                    java.time.Clock.systemUTC(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), readyEvents::add),
            mock(AiHeartbeatService.class),
            new AiRecoveryRegistry()
    );

    @BeforeEach
    void setUpMeeting() {
        Meeting meeting = mock(Meeting.class);
        Team team = mock(Team.class);
        when(meetingRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(meeting));
        when(meeting.getTeam()).thenReturn(team);
        when(team.getId()).thenReturn(7L);
        when(meeting.getTitle()).thenReturn("주간 스프린트 회의");
        when(meeting.getPurpose()).thenReturn("STT 맥락 생성 설계 검토");
        when(meeting.getNote()).thenReturn("Modal, Qwen 관련 논의");
    }

    @Test
    void connectSendSessionStartAndMarkReadyWhenSessionReadyReceived() throws Exception {
        boolean started = service.start(context(AudioFormat.PCM_S16LE));

        assertThat(started).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.START_SENT);
        assertThat(client.connectCount).isOne();
        assertSessionStart(client.session.sentMessages.getFirst());

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {
                    "status": "READY",
                    "inputAudioFormat": "pcm_s16le",
                    "outputAudioFormat": "pcm_s16le",
                    "outputSampleRateHz": 16000,
                    "outputChannels": 1
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void pcmSessionStartSucceeds() throws Exception {
        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();

        assertSessionStart(client.session.sentMessages.getFirst());
    }

    @Test
    void duplicateAiConnectionDoesNotCreateNewConnection() {
        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();

        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isFalse();

        assertThat(client.connectCount).isOne();
        assertThat(registry.count()).isOne();
    }

    @Test
    void capacityExceededDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("429 capacity_exceeded");

        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @Test
    void serviceUnavailableDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("503 service_unavailable");

        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"meetingId", "recordingSessionId", "requestId"})
    void mismatchedSessionReadyDoesNotMarkReady(String mismatchField) throws Exception {
        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        String meetingId = "meetingId".equals(mismatchField) ? "43" : "42";
        String recordingSessionId = "recordingSessionId".equals(mismatchField) ? "89" : "88";
        String requestId = "requestId".equals(mismatchField) ? "other-request" : "start-test";

        client.receive(String.format("""
                {
                  "type": "session.ready",
                  "requestId": "%s",
                  "meetingId": %s,
                  "recordingSessionId": %s,
                  "payload": {"status": "READY"}
                }
                """, requestId, meetingId, recordingSessionId));

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.START_SENT);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void aiErrorCleansRegistryWithoutChangingDbState() throws Exception {
        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();

        client.receive("""
                {
                  "type": "error",
                  "requestId": "start-test",
                  "payload": {
                    "code": "provider_unavailable",
                    "message": "provider is unavailable",
                    "retryable": true
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    void connectionFailureDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("connect failed");

        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    @DisplayName("session.start에 회의 생성 시 지정한 참석자와 참석자 수를 담는다")
    void sessionStartIncludesAttendees() throws Exception {
        Meeting meeting = mock(Meeting.class);
        TeamMember member = mock(TeamMember.class);
        when(member.getId()).thenReturn(10L);
        when(member.getDisplayName()).thenReturn("김지우");
        when(meetingParticipantRepository.findAttendeesWithVoiceProfilesByMeetingId(42L)).thenReturn(List.of(
                MeetingParticipant.createAttendee(meeting, member),
                MeetingParticipant.createCustomAttendee(meeting, "외부 디자이너")));

        service.start(context(AudioFormat.PCM_S16LE));

        JsonNode root = objectMapper.readTree(client.session.sentMessages.getFirst());
        assertThat(root.path("participantCount").asInt()).isEqualTo(2);
        assertThat(root.path("participants").get(0).path("teamMemberId").asLong()).isEqualTo(10L);
        assertThat(root.path("participants").get(0).path("name").asText()).isEqualTo("김지우");
        assertThat(root.path("participants").get(1).has("teamMemberId")).isTrue();
        assertThat(root.path("participants").get(1).path("teamMemberId").isNull()).isTrue();
        assertThat(root.path("participants").get(1).path("name").asText()).isEqualTo("외부 디자이너");
    }

    @Test
    @DisplayName("session.start는 참석자의 192차원 음성 프로필을 포함하고 사용자 프로필이 없으면 null을 보낸다")
    void sessionStartIncludesVoiceProfilesWithinMessageLimit() throws Exception {
        Meeting meeting = mock(Meeting.class);
        List<MeetingParticipant> attendees = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            TeamMember member = mock(TeamMember.class);
            User user = User.create();
            List<Double> embedding = java.util.Collections.nCopies(192, 0.123456789d);
            user.registerVoiceProfile("profile-" + i, embedding, "redimnet2-plus-fd45d19-v1");
            when(member.getId()).thenReturn((long) i + 1);
            when(member.getDisplayName()).thenReturn("멤버" + i);
            when(member.getUser()).thenReturn(user);
            attendees.add(MeetingParticipant.createAttendee(meeting, member));
        }
        attendees.add(MeetingParticipant.createCustomAttendee(meeting, "외부 참석자"));
        when(meetingParticipantRepository.findAttendeesWithVoiceProfilesByMeetingId(42L)).thenReturn(attendees);

        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();

        String payload = client.session.sentMessages.getFirst();
        JsonNode root = objectMapper.readTree(payload);
        JsonNode participant = root.path("participants").get(0);
        assertThat(participant.path("speakerEmbedding").path("modelVersion").asText())
                .isEqualTo("redimnet2-plus-fd45d19-v1");
        assertThat(participant.path("speakerEmbedding").path("embedding").size()).isEqualTo(192);
        assertThat(root.path("participants").get(9).has("speakerEmbedding")).isTrue();
        assertThat(root.path("participants").get(9).path("speakerEmbedding").isNull()).isTrue();
        assertThat(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_536);
    }

    @Test
    @DisplayName("비고가 없는 회의의 session.start에서는 notes 필드를 생략한다")
    void sessionStartOmitsNullNotes() throws Exception {
        when(meetingRepository.findByIdAndDeletedAtIsNull(42L).orElseThrow().getNote()).thenReturn(null);

        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();

        JsonNode root = objectMapper.readTree(client.session.sentMessages.getFirst());
        assertThat(root.has("notes")).isFalse();
        assertThat(root.path("teamId").asLong()).isEqualTo(7L);
    }

    @Test
    void stopSendsSessionStopThenEndedClosesAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertSessionStop(client.session.sentMessages.get(1));
        assertThat(timeoutScheduler.scheduledDelay).isEqualTo(Duration.ofSeconds(30));

        client.receive("""
                {
                  "type": "session.ended",
                  "requestId": "stop-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {
                    "status": "ENDED",
                    "lastSequence": null,
                    "audioDurationMs": 3600000
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(client.session.closeStatuses).contains(CloseStatus.NORMAL);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(timeoutScheduler.future.cancelled).isTrue();
    }

    @Test
    @DisplayName("DB 보정 STOP은 검증한 연결에만 한 번 전송하고 session.ended 후 전사를 확정한다")
    void reconciledStopUsesExpectedConnectionAndFinalizesOnce() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");

        assertThat(service.stopCurrent(connection, "server-a:1")).isTrue();
        assertThat(service.stopCurrent(connection, "server-a:1")).isFalse();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(client.session.sentMessages).hasSize(2);

        client.receive(sessionEnded());
        timeoutScheduler.runScheduledTask();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(readyEvents.stream().filter(MeetingTranscriptFinalizedEvent.class::isInstance)).hasSize(1);
        verify(ownerService).release(88L, "server-a:1");
    }

    @Test
    @DisplayName("Pub/Sub STOP과 DB 보정 STOP이 동시에 실행돼도 session.stop은 한 번만 보낸다")
    void pubSubAndReconciliationStopRaceSendsOnce() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable pubSub = () -> {
            ready.countDown();
            try {
                start.await();
                service.stop(88L);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        };
        Runnable reconciliation = () -> {
            ready.countDown();
            try {
                start.await();
                service.stopCurrent(connection, "server-a:1");
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        };
        Thread first = Thread.ofVirtual().start(pubSub);
        Thread second = Thread.ofVirtual().start(reconciliation);
        ready.await();
        start.countDown();
        first.join();
        second.join();

        assertThat(failure.get()).isNull();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(client.session.sentMessages).hasSize(2);
        assertSessionStop(client.session.sentMessages.getLast());
    }

    @Test
    @DisplayName("검증 당시 Owner와 현재 연결 Owner가 다르면 보정 STOP을 보내지 않는다")
    void reconciledStopRejectsChangedOwnerAndFencedConnection() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-b:2");

        assertThat(service.stopCurrent(connection, "server-a:1")).isFalse();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(client.session.sentMessages).hasSize(1);

        connection.assignOwner("server-a:1");
        connection.fenceForRecovery();
        assertThat(service.stopCurrent(connection, "server-a:1")).isFalse();
        assertThat(client.session.sentMessages).hasSize(1);
    }

    @Test
    @DisplayName("AI PAUSE 응답 대기 중에도 종료 목표가 확인되면 STOP을 전송한다")
    void reconciledStopPreemptsPauseSent() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");
        service.pause(88L);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.PAUSE_SENT);

        assertThat(service.stopCurrent(connection, "server-a:1")).isTrue();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertSessionStop(client.session.sentMessages.getLast());
    }

    @Test
    void stopPayloadContainsOnlyTypeAndRequestId() throws Exception {
        readyConnection();

        service.stop(88L);

        JsonNode root = objectMapper.readTree(client.session.sentMessages.get(1));
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("type").asText()).isEqualTo("session.stop");
        assertThat(root.path("requestId").asText()).isEqualTo("stop-test");
        assertThat(root.has("payload")).isFalse();
        assertThat(root.has("finalAudioEndMs")).isFalse();
    }

    @Test
    @DisplayName("READY 상태에서 pause를 요청하면 기존 AI connection으로 session.pause를 전송한다")
    void pauseSendsSessionPauseWithExistingConnection() throws Exception {
        // 테스트 목적:
        // AI connection이 READY인 상태에서 녹음 일시정지 이벤트가 처리되면
        // 새 connection 생성 없이 기존 connection으로 명세에 맞는 session.pause가 전송되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = readyConnection();

        // when
        service.pause(88L);

        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.PAUSE_SENT);
        assertSessionPause(client.session.sentMessages.get(1));
        assertThat(client.connectCount).isOne();
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    @DisplayName("session.paused를 받으면 AI connection 상태를 PAUSED로 반영한다")
    void sessionPausedMarksConnectionPaused() throws Exception {
        // 테스트 목적:
        // session.pause 요청과 같은 requestId의 session.paused ACK를 수신했을 때
        // AI connection 상태가 PAUSED로 변경되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = readyConnection();
        service.pause(88L);

        // when
        client.receive("""
                {
                  "type": "session.paused",
                  "requestId": "pause-test",
                  "payload": {"status": "PAUSED"}
                }
                """);

        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.PAUSED);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    @DisplayName("PAUSED 상태에서 resume을 요청하면 기존 AI connection으로 session.resume을 전송한다")
    void resumeSendsSessionResumeWithExistingConnection() throws Exception {
        // 테스트 목적:
        // AI connection이 PAUSED인 상태에서 녹음 재개 이벤트가 처리되면
        // 새 connection 생성 없이 기존 connection으로 명세에 맞는 session.resume이 전송되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = pausedConnection();

        // when
        service.resume(88L);

        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.RESUME_SENT);
        assertSessionResume(client.session.sentMessages.get(2));
        assertThat(client.connectCount).isOne();
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    @DisplayName("session.resumed를 받으면 AI connection 상태를 READY로 반영한다")
    void sessionResumedMarksConnectionReady() throws Exception {
        // 테스트 목적:
        // session.resume 요청과 같은 requestId의 session.resumed ACK를 수신했을 때
        // AI connection 상태가 READY로 변경되어 오디오 전송 가능 상태가 되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = pausedConnection();
        service.resume(88L);

        // when
        client.receive("""
                {
                  "type": "session.resumed",
                  "requestId": "resume-test",
                  "payload": {"status": "READY"}
                }
                """);

        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    @DisplayName("pause와 resume payload는 type과 requestId만 포함한다")
    void pauseAndResumePayloadContainOnlyTypeAndRequestId() throws Exception {
        // 테스트 목적:
        // AI 명세에 정의되지 않은 meetingId, recordingSessionId, payload가
        // session.pause와 session.resume 메시지에 포함되지 않는지 검증한다.

        // given
        pausedConnection();

        // when
        service.resume(88L);

        // then
        JsonNode pause = objectMapper.readTree(client.session.sentMessages.get(1));
        JsonNode resume = objectMapper.readTree(client.session.sentMessages.get(2));
        assertThat(pause.size()).isEqualTo(2);
        assertThat(pause.path("type").asText()).isEqualTo("session.pause");
        assertThat(pause.path("requestId").asText()).isEqualTo("pause-test");
        assertThat(pause.has("meetingId")).isFalse();
        assertThat(pause.has("recordingSessionId")).isFalse();
        assertThat(pause.has("payload")).isFalse();
        assertThat(resume.size()).isEqualTo(2);
        assertThat(resume.path("type").asText()).isEqualTo("session.resume");
        assertThat(resume.path("requestId").asText()).isEqualTo("resume-test");
        assertThat(resume.has("meetingId")).isFalse();
        assertThat(resume.has("recordingSessionId")).isFalse();
        assertThat(resume.has("payload")).isFalse();
    }

    @Test
    void stopWithMissingConnectionIsNoop() {
        service.stop(88L);

        assertThat(client.session.sentMessages).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP_SENT", "ENDED", "CLOSED"})
    void duplicateStopDoesNotSendAgain(String state) throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);
        if ("ENDED".equals(state)) {
            connection.markEnded();
        }
        if ("CLOSED".equals(state)) {
            connection.close();
        }

        service.stop(88L);

        assertThat(client.session.sentMessages).hasSize(2);
    }

    @Test
    void stopBeforeReadyDoesNotSendSessionStopAndCleansRegistry() {
        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(client.session.sentMessages).hasSize(1);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(readyEvents).containsExactly(new MeetingTranscriptFinalizedEvent(42L, 88L));
    }

    @Test
    @DisplayName("PAUSED 상태에서 stop을 요청하면 resume 없이 session.stop을 전송한다")
    void stopWhenPausedSendsSessionStopWithoutResume() throws Exception {
        // 테스트 목적:
        // AI connection이 PAUSED인 상태에서 녹음 종료 이벤트가 처리되면
        // session.resume 없이 기존 connection으로 session.stop이 전송되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = pausedConnection();

        // when
        service.stop(88L);

        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(client.session.sentMessages).hasSize(3);
        assertSessionStop(client.session.sentMessages.get(2));
    }

    @Test
    void stopSendFailurePublishesTranscriptFinalizedAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        client.session.open = false;

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(readyEvents).contains(new MeetingTranscriptFinalizedEvent(42L, 88L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "meetingId", "recordingSessionId", "status"})
    void mismatchedSessionEndedDoesNotCleanupAsNormalEnded(String mismatchField) throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);
        String requestId = "requestId".equals(mismatchField) ? "other-stop" : "stop-test";
        String meetingId = "meetingId".equals(mismatchField) ? "43" : "42";
        String recordingSessionId = "recordingSessionId".equals(mismatchField) ? "89" : "88";
        String status = "status".equals(mismatchField) ? "FAILED" : "ENDED";

        client.receive(String.format("""
                {
                  "type": "session.ended",
                  "requestId": "%s",
                  "meetingId": %s,
                  "recordingSessionId": %s,
                  "payload": {"status": "%s"}
                }
                """, requestId, meetingId, recordingSessionId, status));

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(registry.find(88L)).contains(connection);
        assertThat(client.session.open).isTrue();
    }

    @Test
    void transcriptCommittedDuringStopSentDoesNotCleanupConnection() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {
                    "sequenceNumber": 99,
                    "content": "last transcript",
                    "startedAtMs": 176200,
                    "endedAtMs": 179800,
                    "recognizedAt": "2026-09-21T05:30:04.500Z"
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(registry.find(88L)).contains(connection);
        assertThat(client.session.open).isTrue();
    }

    @Test
    @DisplayName("AI 전사 메시지의 speakerId 0을 그대로 전사 저장 서비스에 전달한다")
    void transcriptSegmentFinalIsSavedWhenReady() throws Exception {
        // 테스트 목적:
        // READY 상태에서 AI가 전달한 화자 식별자 0이 누락되지 않고 전사 저장 경로로 전달되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = readyConnection();

        // when
        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {
                    "sequenceNumber": 31,
                    "content": "final text",
                    "startedAtMs": 176200,
                    "endedAtMs": 179800,
                    "speakerId": 0,
                    "recognizedAt": "2026-09-21T05:30:04.500Z"
                  }
                }
                """);

        // then
        verify(transcriptService).saveFinalSegment(new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                42L,
                88L,
                new AiTranscriptSegmentPayload(
                        31L,
                        "final text",
                        176200L,
                        179800L,
                        LocalDateTime.of(2026, 9, 21, 5, 30, 4, 500_000_000),
                        0L
                )
        ));
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    @DisplayName("화자 식별자가 없는 마지막 전사도 STOP_SENT 상태에서 저장한다")
    void transcriptSegmentFinalIsSavedWhenStopSent() throws Exception {
        // 테스트 목적:
        // AI가 화자 식별자 없이 전사를 보내더라도 기존 종료 중 전사 수신 동작이 유지되는지 검증한다.

        // given
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        // when
        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {
                    "sequenceNumber": 32,
                    "content": "last final text",
                    "startedAtMs": 180000,
                    "recognizedAt": "2026-09-21T05:30:05.000Z"
                  }
                }
                """);

        // then
        verify(transcriptService).saveFinalSegment(new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                42L,
                88L,
                new AiTranscriptSegmentPayload(32L, "last final text", 180000L, null,
                        LocalDateTime.of(2026, 9, 21, 5, 30, 5), null)
        ));
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void errorDuringStopSentClosesAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "error",
                  "requestId": "stop-test",
                  "payload": {"code": "processing_timeout", "message": "timeout", "retryable": false}
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(timeoutScheduler.future.cancelled).isTrue();
    }

    @Test
    void stopTimeoutClosesAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        timeoutScheduler.runScheduledTask();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(client.session.closeStatuses).contains(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("session.ended로 종료되면 요약 이벤트를 발행한 뒤 자신의 Owner를 해제한다")
    void releasesOwnerAfterTranscriptFinalizedOnSessionEnded() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");
        doAnswer(invocation -> {
            assertThat(readyEvents).contains(new MeetingTranscriptFinalizedEvent(42L, 88L));
            return null;
        }).when(ownerService).release(88L, "server-a:1");
        service.stop(88L);

        client.receive(sessionEnded());

        verify(ownerService).release(88L, "server-a:1");
    }

    @Test
    @DisplayName("session.ended를 30초 안에 받지 못해도 요약 이벤트를 발행한 뒤 Owner를 해제한다")
    void releasesOwnerOnStopTimeout() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");
        service.stop(88L);

        timeoutScheduler.runScheduledTask();

        verify(ownerService).release(88L, "server-a:1");
    }

    @Test
    @DisplayName("녹음 중 retry 가능한 AI 오류는 Owner를 유지한 채 기존 Recovery 진입점으로 전달한다")
    void keepsOwnerWhenAiErrorDuringRecording() throws Exception {
        // 테스트 목적:
        // retry 가능한 오류를 즉시 종료하지 않고 공통 Recovery가 판단하도록 위임하는지 검증한다.
        // given
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");

        // when
        client.receive("""
                {
                  "type": "error",
                  "requestId": "start-test",
                  "payload": {
                    "code": "provider_unavailable",
                    "message": "provider is unavailable",
                    "retryable": true
                  }
                }
                """);

        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
        assertThat(readyEvents).contains(new com.backend.meety.domain.ai.event.AiRecoverableFailureEvent(
                88L, connection.sessionStartRequestId(), "AI_PROCESSING_ERROR"));
        verify(ownerService, never()).release(anyLong(), anyString());
    }

    @Test
    @DisplayName("session.ended 없이 AI가 연결을 닫으면 Owner를 해제하지 않는다")
    void keepsOwnerWhenRemoteClosesBeforeSessionEnded() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.assignOwner("server-a:1");
        service.stop(88L);

        client.closeFromRemote(CloseStatus.NORMAL);

        verify(ownerService, never()).release(anyLong(), anyString());
    }

    @Test
    @DisplayName("Owner를 기록하지 않은 연결은 종료해도 해제를 요청하지 않는다")
    void skipsReleaseWhenConnectionHasNoOwner() throws Exception {
        readyConnection();
        service.stop(88L);

        client.receive(sessionEnded());

        verify(ownerService, never()).release(anyLong(), anyString());
    }

    private String sessionEnded() {
        return """
                {
                  "type": "session.ended",
                  "requestId": "stop-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {
                    "status": "ENDED",
                    "lastSequence": null,
                    "audioDurationMs": 3600000
                  }
                }
                """;
    }

    @Test
    void remoteCloseBeforeSessionEndedIsNotNormalEndedAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.closeFromRemote(CloseStatus.NORMAL);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    void cleanupIsSafeWhenSessionEndedCloseAndTimeoutOverlap() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "session.ended",
                  "requestId": "stop-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {"status": "ENDED", "audioDurationMs": 1}
                }
                """);
        client.closeFromRemote(CloseStatus.NORMAL);
        timeoutScheduler.runScheduledTask();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    private AiLiveMeetingConnection readyConnection() throws Exception {
        assertThat(service.start(context(AudioFormat.PCM_S16LE))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {"status": "READY"}
                }
                """);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        return connection;
    }

    private AiLiveMeetingConnection pausedConnection() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        pauseCurrentReadyConnection();
        return connection;
    }

    private void pauseCurrentReadyConnection() throws Exception {
        service.pause(88L);
        client.receive("""
                {
                  "type": "session.paused",
                  "requestId": "pause-test",
                  "payload": {"status": "PAUSED"}
                }
                """);
        assertThat(registry.find(88L).orElseThrow().state()).isEqualTo(AiLiveMeetingConnectionState.PAUSED);
    }

    @Test
    @DisplayName("READY 상태에서 오디오를 전달하면 audio.meta와 바이너리가 순서대로 전송된다")
    void forwardAudioSendsMetaThenBinaryWhenReady() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();

        boolean forwarded = forwardAudio(88L, new byte[]{1, 2, 3});

        assertThat(forwarded).isTrue();
        JsonNode meta = objectMapper.readTree(client.session.sentMessages.getLast());
        assertThat(meta.path("type").asText()).isEqualTo("audio.meta");
        assertThat(meta.path("payload").path("sequence").asLong()).isZero();
        assertThat(client.session.sentBinaries).hasSize(1);
        assertThat(client.session.sentBinaries.getFirst()).containsExactly(1, 2, 3);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
    }

    @Test
    @DisplayName("PAUSED 상태와 resume ACK 전에는 오디오를 AI로 전달하지 않는다")
    void forwardAudioRejectedWhenPausedOrResumeSent() throws Exception {
        // 테스트 목적:
        // AI connection이 PAUSED이거나 session.resume ACK를 받기 전인 경우
        // FE가 오디오를 보내더라도 AI로 audio.meta와 binary가 전달되지 않는지 검증한다.

        // given
        AiLiveMeetingConnection connection = pausedConnection();

        // when
        boolean pausedForwarded = forwardAudio(88L, new byte[]{1});
        service.resume(88L);
        boolean resumeSentForwarded = forwardAudio(88L, new byte[]{2});

        // then
        assertThat(pausedForwarded).isFalse();
        assertThat(resumeSentForwarded).isFalse();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.RESUME_SENT);
        assertThat(client.session.sentBinaries).isEmpty();
        assertThat(audioSequences()).isEmpty();
    }

    @Test
    @DisplayName("session.resumed 이후 오디오 전송이 재개되고 sequence는 이어진다")
    void forwardAudioResumesAfterSessionResumedAndKeepsSequence() throws Exception {
        // 테스트 목적:
        // pause/resume 전후에도 audio sequence가 초기화되지 않고
        // session.resumed 수신 이후에만 오디오 전송이 재개되는지 검증한다.

        // given
        readyConnection();
        forwardAudio(88L, new byte[]{1});
        pauseCurrentReadyConnection();
        service.resume(88L);
        forwardAudio(88L, new byte[]{2});

        // when
        client.receive("""
                {
                  "type": "session.resumed",
                  "requestId": "resume-test",
                  "payload": {"status": "READY"}
                }
                """);
        boolean forwarded = forwardAudio(88L, new byte[]{3});

        // then
        assertThat(forwarded).isTrue();
        assertThat(audioSequences()).containsExactly(0L, 1L);
        assertThat(client.session.sentBinaries).hasSize(2);
        assertThat(client.session.sentBinaries.get(1)).containsExactly(3);
    }

    @Test
    @DisplayName("pause 요청은 진행 중인 audio.meta와 binary 쌍 뒤에 전송된다")
    void pauseDoesNotInterleaveBetweenAudioMetaAndBinary() throws Exception {
        // 테스트 목적:
        // audio.meta 전송 직후 pause 요청이 동시에 발생해도
        // 동일한 직렬화 경계로 인해 binary 전송 뒤에 session.pause가 전송되는지 검증한다.

        // given
        readyConnection();
        AtomicReference<Thread> pauseThread = new AtomicReference<>();
        client.session.onAudioMeta = () -> {
            Thread thread = new Thread(() -> service.pause(88L));
            pauseThread.set(thread);
            thread.start();
        };

        // when
        boolean forwarded = forwardAudio(88L, new byte[]{1});
        pauseThread.get().join(3_000);

        // then
        assertThat(forwarded).isTrue();
        assertThat(client.session.sentFrameTypes)
                .containsExactly("session.start", "audio.meta", "binary", "session.pause");
    }

    @Test
    @DisplayName("오디오를 연속으로 전달하면 sequence가 0부터 순서대로 증가한다")
    void forwardAudioIncrementsSequence() throws Exception {
        readyConnection();

        forwardAudio(88L, new byte[]{1});
        forwardAudio(88L, new byte[]{2});
        forwardAudio(88L, new byte[]{3});

        List<Long> sequences = new ArrayList<>();
        for (String message : client.session.sentMessages) {
            JsonNode root = objectMapper.readTree(message);
            if ("audio.meta".equals(root.path("type").asText())) {
                sequences.add(root.path("payload").path("sequence").asLong());
            }
        }
        assertThat(sequences).containsExactly(0L, 1L, 2L);
        assertThat(client.session.sentBinaries).hasSize(3);
    }

    @Test
    @DisplayName("session.ready를 받기 전에 온 오디오는 전달하지 않는다")
    void forwardAudioRejectedBeforeReady() throws Exception {
        service.start(context(AudioFormat.PCM_S16LE));

        boolean forwarded = forwardAudio(88L, new byte[]{1, 2, 3});

        assertThat(forwarded).isFalse();
        assertThat(client.session.sentBinaries).isEmpty();
    }

    @Test
    @DisplayName("AI 연결이 없으면 오디오를 전달하지 않는다")
    void forwardAudioRejectedWhenConnectionMissing() {
        boolean forwarded = forwardAudio(88L, new byte[]{1, 2, 3});

        assertThat(forwarded).isFalse();
    }

    @Test
    @DisplayName("session.ready를 받으면 AI 준비 완료 이벤트를 발행한다")
    void publishReadyEventWhenSessionReadyReceived() throws Exception {
        readyConnection();

        assertThat(readyEvents).hasSize(1);
        assertThat(readyEvents.getFirst())
                .isInstanceOf(AiLiveMeetingReadyEvent.class)
                .extracting("recordingSessionId")
                .isEqualTo(88L);
    }

    @Test
    @DisplayName("session.ready가 현재 connection과 일치하지 않으면 이벤트를 발행하지 않는다")
    void doesNotPublishReadyEventWhenSessionReadyMismatched() throws Exception {
        service.start(context(AudioFormat.PCM_S16LE));

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "other-request",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": { "status": "READY" }
                }
                """);

        assertThat(readyEvents).isEmpty();
    }

    @Test
    @DisplayName("session.ready를 받으면 핸드셰이크 대기가 성공으로 끝난다")
    void startAndAwaitReadySucceedsWhenSessionReadyArrives() throws Exception {
        Thread waiting = new Thread(() -> readyResult.set(service.startAndAwaitReady(context(AudioFormat.PCM_S16LE))));
        waiting.start();
        awaitSessionStartSent();

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": { "status": "READY" }
                }
                """);
        waiting.join(3_000);

        assertThat(readyResult.get()).isTrue();
        assertThat(registry.find(88L)).isPresent();
    }

    @Test
    @DisplayName("AI 연결에 실패하면 핸드셰이크 대기도 실패한다")
    void startAndAwaitReadyFailsWhenConnectionFails() {
        client.failure = new RuntimeException("connect failed");

        assertThat(service.startAndAwaitReady(context(AudioFormat.PCM_S16LE))).isFalse();
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    @DisplayName("AI 연결이 없으면 session.start로 연결하고 session.ready를 받으면 준비 완료를 돌려준다")
    void prepareStreamStartsConnection() throws Exception {
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiting = new Thread(() -> result.set(service.prepareStream(context(AudioFormat.PCM_S16LE))));
        waiting.start();
        awaitSentType("session.start");
        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": 42,
                  "recordingSessionId": 88,
                  "payload": {"status": "READY"}
                }
                """);
        waiting.join(3_000);

        assertThat(result.get()).isTrue();
        assertThat(client.connectCount).isOne();
        assertThat(client.session.sentFrameTypes).containsExactly("session.start");
        assertThat(registry.find(88L).orElseThrow().state()).isEqualTo(AiLiveMeetingConnectionState.READY);
    }

    @Test
    @DisplayName("AI 연결이 있으면 새로 연결하지 않고 그대로 사용한다")
    void prepareStreamReusesConnection() throws Exception {
        readyConnection();

        boolean prepared = service.prepareStream(context(AudioFormat.PCM_S16LE));

        assertThat(prepared).isTrue();
        assertThat(client.connectCount).isOne();
        assertThat(client.session.sentFrameTypes).containsExactly("session.start");
    }

    @Test
    @DisplayName("READY인 AI 연결은 바로 스트림을 받을 수 있다")
    void awaitStreamableReturnsImmediatelyWhenReady() throws Exception {
        readyConnection();

        assertThat(service.awaitStreamable(88L)).isTrue();
    }

    @Test
    @DisplayName("재개 응답을 기다리는 중이면 session.resumed를 받은 뒤 스트림을 받을 수 있다")
    void awaitStreamableWaitsForResumed() throws Exception {
        pausedConnection();
        service.resume(88L);
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiting = new Thread(() -> result.set(service.awaitStreamable(88L)));
        waiting.start();
        awaitSentType("session.resume");

        client.receive("""
                {"type":"session.resumed","requestId":"resume-test","payload":{"status":"READY"}}
                """);
        waiting.join(3_000);

        assertThat(result.get()).isTrue();
    }

    @Test
    @DisplayName("AI 연결이 없으면 스트림을 받을 수 없다")
    void awaitStreamableFailsWithoutConnection() {
        assertThat(service.awaitStreamable(88L)).isFalse();
    }

    @Test
    void controlAckTimeoutIsClaimedOnceAndRoutesThroughExistingRecovery() throws Exception {
        AiLiveMeetingConnection connection = new AiLiveMeetingConnection(99L, 42L, "start-99");
        connection.assignOwner("server-a:99");
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        connection.attach(session);
        connection.markReady();
        registry.reserve(connection);
        assertThat(connection.sendPause(objectMapper, "pause-99")).isTrue();

        assertThat(connection.claimControlAckTimeout(Duration.ZERO)).isTrue();
        assertThat(connection.claimControlAckTimeout(Duration.ZERO)).isFalse();
        service.controlAckTimedOut(connection);

        assertThat(readyEvents).contains(new AiRecoverableFailureEvent(99L, "start-99", "CONTROL_ACK_TIMEOUT"));
    }

    private long feSequence;

    private boolean forwardAudio(Long id, byte[] bytes) {
        return service.forwardAudio(id, ++feSequence, bytes);
    }

    private void awaitSentType(String type) throws InterruptedException {
        for (int i = 0; i < 300 && !client.session.sentFrameTypes.contains(type); i++) {
            Thread.sleep(10);
        }
    }

    private void awaitSessionStartSent() throws InterruptedException {
        for (int i = 0; i < 100 && client.session.sentMessages.isEmpty(); i++) {
            Thread.sleep(10);
        }
    }

    private AudioWebSocketContext context(AudioFormat audioFormat) {
        return new AudioWebSocketContext(7L, 42L, 88L, audioFormat);
    }

    private void assertSessionStart(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(10);
        assertThat(root.path("type").asText()).isEqualTo("session.start");
        assertThat(root.path("requestId").asText()).isEqualTo("start-test");
        assertThat(root.path("meetingId").isIntegralNumber()).isTrue();
        assertThat(root.path("meetingId").asLong()).isEqualTo(42L);
        assertThat(root.path("recordingSessionId").isIntegralNumber()).isTrue();
        assertThat(root.path("recordingSessionId").asLong()).isEqualTo(88L);
        assertThat(root.path("teamId").asLong()).isEqualTo(7L);
        assertThat(root.path("title").asText()).isEqualTo("주간 스프린트 회의");
        assertThat(root.path("purpose").asText()).isEqualTo("STT 맥락 생성 설계 검토");
        assertThat(root.path("notes").asText()).isEqualTo("Modal, Qwen 관련 논의");
        assertThat(root.has("payload")).isFalse();
        assertThat(root.path("participantCount").asInt()).isZero();
        assertThat(root.path("participants").isArray()).isTrue();
        assertThat(root.path("participants")).isEmpty();
    }

    private void assertSessionStop(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("type").asText()).isEqualTo("session.stop");
        assertThat(root.path("requestId").asText()).isEqualTo("stop-test");
    }

    private void assertSessionPause(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("type").asText()).isEqualTo("session.pause");
        assertThat(root.path("requestId").asText()).isEqualTo("pause-test");
    }

    private void assertSessionResume(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("type").asText()).isEqualTo("session.resume");
        assertThat(root.path("requestId").asText()).isEqualTo("resume-test");
    }

    private List<Long> audioSequences() throws Exception {
        List<Long> sequences = new ArrayList<>();
        for (String message : client.session.sentMessages) {
            JsonNode root = objectMapper.readTree(message);
            if ("audio.meta".equals(root.path("type").asText())) {
                sequences.add(root.path("payload").path("sequence").asLong());
            }
        }
        return sequences;
    }

    private static class FixedAiRequestIdGenerator extends AiRequestIdGenerator {

        @Override
        public String sessionStartRequestId() {
            return "start-test";
        }

        @Override
        public String sessionStopRequestId() {
            return "stop-test";
        }

        @Override
        public String sessionPauseRequestId() {
            return "pause-test";
        }

        @Override
        public String sessionResumeRequestId() {
            return "resume-test";
        }

    }

    private static class TestAiWebSocketClient implements AiLiveMeetingWebSocketClient {

        private final TestWebSocketSession session = new TestWebSocketSession();
        private WebSocketHandler handler;
        private RuntimeException failure;
        private int connectCount;

        @Override
        public WebSocketSession connect(WebSocketHandler handler, URI uri) {
            connectCount++;
            if (failure != null) {
                throw failure;
            }
            this.handler = handler;
            return session;
        }

        private void receive(String payload) throws Exception {
            handler.handleMessage(session, new TextMessage(payload));
        }

        private void closeFromRemote(CloseStatus status) throws Exception {
            session.open = false;
            handler.afterConnectionClosed(session, status);
        }
    }

    private static class TestWebSocketSession implements WebSocketSession {

        private final WebSocketSession delegate = mock(WebSocketSession.class);
        private final List<String> sentMessages = new CopyOnWriteArrayList<>();
        private final List<String> sentFrameTypes = new CopyOnWriteArrayList<>();
        private final List<byte[]> sentBinaries = new ArrayList<>();
        private final List<CloseStatus> closeStatuses = new ArrayList<>();
        private Runnable onAudioMeta;
        private boolean open = true;

        private TestWebSocketSession() {
            when(delegate.getId()).thenReturn("ai-session");
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            if (message instanceof BinaryMessage binaryMessage) {
                ByteBuffer payload = binaryMessage.getPayload();
                byte[] audio = new byte[payload.remaining()];
                payload.get(audio);
                sentBinaries.add(audio);
                sentFrameTypes.add("binary");
                return;
            }
            String payload = (String) message.getPayload();
            sentMessages.add(payload);
            String type = extractType(payload);
            sentFrameTypes.add(type);
            if ("audio.meta".equals(type) && onAudioMeta != null) {
                onAudioMeta.run();
            }
        }

        private String extractType(String payload) {
            String marker = "\"type\":\"";
            int start = payload.indexOf(marker);
            if (start < 0) {
                return "text";
            }
            int valueStart = start + marker.length();
            int valueEnd = payload.indexOf('"', valueStart);
            return payload.substring(valueStart, valueEnd);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(org.springframework.web.socket.CloseStatus status) {
            open = false;
            closeStatuses.add(status);
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public java.net.URI getUri() {
            return delegate.getUri();
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return delegate.getHandshakeHeaders();
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public java.security.Principal getPrincipal() {
            return delegate.getPrincipal();
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return delegate.getRemoteAddress();
        }

        @Override
        public String getAcceptedProtocol() {
            return delegate.getAcceptedProtocol();
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            delegate.setTextMessageSizeLimit(messageSizeLimit);
        }

        @Override
        public int getTextMessageSizeLimit() {
            return delegate.getTextMessageSizeLimit();
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            delegate.setBinaryMessageSizeLimit(messageSizeLimit);
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return delegate.getBinaryMessageSizeLimit();
        }

        @Override
        public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return delegate.getExtensions();
        }
    }

    private static class TestAiStopTimeoutScheduler implements AiStopTimeoutScheduler {

        private Runnable task;
        private Duration scheduledDelay;
        private TestScheduledFuture future;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
            this.task = task;
            this.scheduledDelay = delay;
            this.future = new TestScheduledFuture();
            return future;
        }

        private void runScheduledTask() {
            if (task != null && !future.cancelled) {
                task.run();
            }
        }
    }

    private static class TestScheduledFuture implements ScheduledFuture<Object> {

        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
