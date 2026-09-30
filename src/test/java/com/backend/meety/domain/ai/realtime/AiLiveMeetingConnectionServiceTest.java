package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
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
    private final AiLiveMeetingConnectionService service = new AiLiveMeetingConnectionService(
            registry,
            client,
            new AiLiveMeetingProperties(URI.create("ws://localhost:8000/v1/live-meeting")),
            new FixedAiRequestIdGenerator(),
            timeoutScheduler,
            objectMapper,
            readyEvents::add,
            transcriptService
    );

    @Test
    void connectSendSessionStartAndMarkReadyWhenSessionReadyReceived() throws Exception {
        boolean started = service.start(context(AudioFormat.WEBM_OPUS));

        assertThat(started).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.START_SENT);
        assertThat(client.connectCount).isOne();
        assertSessionStart(client.session.sentMessages.getFirst(), "webm_opus");

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "status": "READY",
                    "inputAudioFormat": "webm_opus",
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
    void webmOpusSessionStartSucceeds() throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();

        assertSessionStart(client.session.sentMessages.getFirst(), "webm_opus");
    }

    @Test
    void mp4AacSessionStartSucceeds() throws Exception {
        assertThat(service.start(context(AudioFormat.MP4_AAC))).isTrue();

        assertSessionStart(client.session.sentMessages.getFirst(), "mp4_aac");
    }

    @Test
    void duplicateAiConnectionDoesNotCreateNewConnection() {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(client.connectCount).isOne();
        assertThat(registry.count()).isOne();
    }

    @Test
    void capacityExceededDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("429 capacity_exceeded");

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @Test
    void serviceUnavailableDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("503 service_unavailable");

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"meetingId", "recordingSessionId", "requestId"})
    void mismatchedSessionReadyDoesNotMarkReady(String mismatchField) throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        String meetingId = "meetingId".equals(mismatchField) ? "43" : "42";
        String recordingSessionId = "recordingSessionId".equals(mismatchField) ? "89" : "88";
        String requestId = "requestId".equals(mismatchField) ? "other-request" : "start-test";

        client.receive(String.format("""
                {
                  "type": "session.ready",
                  "requestId": "%s",
                  "meetingId": "%s",
                  "recordingSessionId": "%s",
                  "payload": {"status": "READY"}
                }
                """, requestId, meetingId, recordingSessionId));

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.START_SENT);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void aiErrorCleansRegistryWithoutChangingDbState() throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
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

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
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
                  "meetingId": "42",
                  "recordingSessionId": "88",
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
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
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
                  "meetingId": "%s",
                  "recordingSessionId": "%s",
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
                  "meetingId": "42",
                  "recordingSessionId": "88",
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
    void transcriptSegmentFinalIsSavedWhenReady() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();

        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "sequenceNumber": 31,
                    "content": "final text",
                    "startedAtMs": 176200,
                    "endedAtMs": 179800,
                    "recognizedAt": "2026-09-21T05:30:04.500Z"
                  }
                }
                """);

        verify(transcriptService).saveFinalSegment(new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                42L,
                88L,
                new AiTranscriptSegmentPayload(
                        31L,
                        "final text",
                        176200L,
                        179800L,
                        LocalDateTime.of(2026, 9, 21, 5, 30, 4, 500_000_000)
                )
        ));
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void transcriptSegmentFinalIsSavedWhenStopSent() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "sequenceNumber": 32,
                    "content": "last final text",
                    "startedAtMs": 180000,
                    "recognizedAt": "2026-09-21T05:30:05.000Z"
                  }
                }
                """);

        verify(transcriptService).saveFinalSegment(new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                42L,
                88L,
                new AiTranscriptSegmentPayload(32L, "last final text", 180000L, null,
                        LocalDateTime.of(2026, 9, 21, 5, 30, 5))
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
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {"status": "ENDED", "audioDurationMs": 1}
                }
                """);
        client.closeFromRemote(CloseStatus.NORMAL);
        timeoutScheduler.runScheduledTask();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    private AiLiveMeetingConnection readyConnection() throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
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

        boolean forwarded = service.forwardAudio(88L, 0L, new byte[]{1, 2, 3});

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
        boolean pausedForwarded = service.forwardAudio(88L, 0L, new byte[]{1});
        service.resume(88L);
        boolean resumeSentForwarded = service.forwardAudio(88L, 0L, new byte[]{2});

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
        service.forwardAudio(88L, 0L, new byte[]{1});
        pauseCurrentReadyConnection();
        service.resume(88L);
        service.forwardAudio(88L, 0L, new byte[]{2});

        // when
        client.receive("""
                {
                  "type": "session.resumed",
                  "requestId": "resume-test",
                  "payload": {"status": "READY"}
                }
                """);
        boolean forwarded = service.forwardAudio(88L, 0L, new byte[]{3});

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
        boolean forwarded = service.forwardAudio(88L, 0L, new byte[]{1});
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

        service.forwardAudio(88L, 0L, new byte[]{1});
        service.forwardAudio(88L, 0L, new byte[]{2});
        service.forwardAudio(88L, 0L, new byte[]{3});

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
        service.start(context(AudioFormat.WEBM_OPUS));

        boolean forwarded = service.forwardAudio(88L, 0L, new byte[]{1, 2, 3});

        assertThat(forwarded).isFalse();
        assertThat(client.session.sentBinaries).isEmpty();
    }

    @Test
    @DisplayName("AI 연결이 없으면 오디오를 전달하지 않는다")
    void forwardAudioRejectedWhenConnectionMissing() {
        boolean forwarded = service.forwardAudio(88L, 0L, new byte[]{1, 2, 3});

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
        service.start(context(AudioFormat.WEBM_OPUS));

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "other-request",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": { "status": "READY" }
                }
                """);

        assertThat(readyEvents).isEmpty();
    }

    @Test
    @DisplayName("session.ready를 받으면 핸드셰이크 대기가 성공으로 끝난다")
    void startAndAwaitReadySucceedsWhenSessionReadyArrives() throws Exception {
        Thread waiting = new Thread(() -> readyResult.set(service.startAndAwaitReady(context(AudioFormat.WEBM_OPUS))));
        waiting.start();
        awaitSessionStartSent();

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
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

        assertThat(service.startAndAwaitReady(context(AudioFormat.WEBM_OPUS))).isFalse();
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    @DisplayName("AI 연결이 없으면 session.start 후 decoder.reset까지 마치고 새 스트림 세대를 돌려준다")
    void openStreamStartsConnectionThenResetsDecoder() throws Exception {
        AtomicReference<OptionalLong> result = new AtomicReference<>();
        Thread waiting = new Thread(() -> result.set(service.openStream(context(AudioFormat.WEBM_OPUS))));
        waiting.start();
        awaitSentType("session.start");
        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {"status": "READY"}
                }
                """);
        awaitSentType("decoder.reset");
        client.receive(decoderReady("reset-test"));
        waiting.join(3_000);

        assertThat(result.get()).hasValue(1L);
        assertThat(client.connectCount).isOne();
        assertThat(registry.find(88L).orElseThrow().state()).isEqualTo(AiLiveMeetingConnectionState.READY);
    }

    @Test
    @DisplayName("READY인 AI 연결이 있으면 새로 연결하지 않고 새 녹음 형식으로 decoder.reset만 보낸다")
    void openStreamReusesReadyConnection() throws Exception {
        readyConnection();

        OptionalLong streamEpoch = openStreamWithDecoderReady(AudioFormat.MP4_AAC);

        assertThat(streamEpoch).hasValue(1L);
        assertThat(client.connectCount).isOne();
        JsonNode reset = lastSentOfType("decoder.reset");
        assertThat(reset.size()).isEqualTo(3);
        assertThat(reset.path("requestId").asText()).isEqualTo("reset-test");
        assertThat(reset.path("payload").path("audioFormat").asText()).isEqualTo("mp4_aac");
    }

    @Test
    @DisplayName("reset 이후 옛 세대 청크는 버리고 새 세대 청크만 전달하며 시퀀스는 이어진다")
    void oldStreamChunksAreDroppedAfterReset() throws Exception {
        readyConnection();
        assertThat(service.forwardAudio(88L, 0L, new byte[]{1})).isTrue();
        OptionalLong streamEpoch = openStreamWithDecoderReady(AudioFormat.WEBM_OPUS);

        boolean oldForwarded = service.forwardAudio(88L, 0L, new byte[]{2});
        boolean newForwarded = service.forwardAudio(88L, streamEpoch.getAsLong(), new byte[]{3});

        assertThat(oldForwarded).isFalse();
        assertThat(newForwarded).isTrue();
        assertThat(audioSequences()).containsExactly(0L, 1L);
        assertThat(client.session.sentFrameTypes).containsExactly(
                "session.start", "audio.meta", "binary", "decoder.reset", "audio.meta", "binary");
    }

    @Test
    @DisplayName("PAUSED 중 재연결하면 decoder.ready 후에도 PAUSED로 남는다")
    void resetFromPausedReturnsToPaused() throws Exception {
        AiLiveMeetingConnection connection = pausedConnection();

        OptionalLong streamEpoch = openStreamWithDecoderReady(AudioFormat.WEBM_OPUS);

        assertThat(streamEpoch).hasValue(1L);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.PAUSED);
    }

    @Test
    @DisplayName("decoder.reset에 error가 오면 AI 연결을 정리하고 실패한다")
    void openStreamFailsAndCleansUpWhenResetErrors() throws Exception {
        readyConnection();
        AtomicReference<OptionalLong> result = new AtomicReference<>();
        Thread waiting = new Thread(() -> result.set(service.openStream(context(AudioFormat.WEBM_OPUS))));
        waiting.start();
        awaitSentType("decoder.reset");

        client.receive("""
                {"type":"error","requestId":"reset-test","payload":{"code":"DECODER_RESET_FAILED","retryable":false}}
                """);
        waiting.join(3_000);

        assertThat(result.get()).isEmpty();
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    @DisplayName("requestId가 다른 decoder.ready는 무시한다")
    void decoderReadyWithOtherRequestIdIsIgnored() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.sendDecoderReset(objectMapper, "reset-test", AudioFormat.WEBM_OPUS);

        client.receive(decoderReady("reset-other"));

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.RESET_SENT);
    }

    @Test
    @DisplayName("decoder.ready 대기 중 회의가 종료되면 session.stop을 보낸다")
    void stopIsSentWhileResetPending() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        connection.sendDecoderReset(objectMapper, "reset-test", AudioFormat.WEBM_OPUS);

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(client.session.sentFrameTypes).contains("session.stop");
    }

    private OptionalLong openStreamWithDecoderReady(AudioFormat audioFormat) throws Exception {
        AtomicReference<OptionalLong> result = new AtomicReference<>();
        Thread waiting = new Thread(() -> result.set(service.openStream(context(audioFormat))));
        waiting.start();
        awaitSentType("decoder.reset");
        client.receive(decoderReady("reset-test"));
        waiting.join(3_000);
        return result.get();
    }

    private void awaitSentType(String type) throws InterruptedException {
        for (int i = 0; i < 300 && !client.session.sentFrameTypes.contains(type); i++) {
            Thread.sleep(10);
        }
    }

    private String decoderReady(String requestId) {
        return """
                {"type":"decoder.ready","requestId":"%s","payload":{"status":"READY"}}
                """.formatted(requestId);
    }

    private JsonNode lastSentOfType(String type) throws Exception {
        for (int i = client.session.sentMessages.size() - 1; i >= 0; i--) {
            JsonNode root = objectMapper.readTree(client.session.sentMessages.get(i));
            if (type.equals(root.path("type").asText())) {
                return root;
            }
        }
        throw new AssertionError(type + " was not sent");
    }

    private void awaitSessionStartSent() throws InterruptedException {
        for (int i = 0; i < 100 && client.session.sentMessages.isEmpty(); i++) {
            Thread.sleep(10);
        }
    }

    private AudioWebSocketContext context(AudioFormat audioFormat) {
        return new AudioWebSocketContext(7L, 42L, 88L, audioFormat);
    }

    private void assertSessionStart(String json, String audioFormat) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(5);
        assertThat(root.path("type").asText()).isEqualTo("session.start");
        assertThat(root.path("requestId").asText()).isEqualTo("start-test");
        assertThat(root.path("meetingId").isTextual()).isTrue();
        assertThat(root.path("meetingId").asText()).isEqualTo("42");
        assertThat(root.path("recordingSessionId").isTextual()).isTrue();
        assertThat(root.path("recordingSessionId").asText()).isEqualTo("88");
        assertThat(root.path("payload").size()).isOne();
        assertThat(root.path("payload").path("audioFormat").asText()).isEqualTo(audioFormat);
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

        @Override
        public String decoderResetRequestId() {
            return "reset-test";
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
