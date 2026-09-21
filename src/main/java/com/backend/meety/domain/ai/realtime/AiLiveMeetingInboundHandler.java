package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.meeting.realtime.MeetingEventBroadcaster;
import com.backend.meety.domain.meeting.realtime.MeetingEventType;
import com.backend.meety.domain.meeting.realtime.MeetingRealtimeEvent;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.transcript.service.TranscriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiLiveMeetingInboundHandler {

    private final ObjectMapper objectMapper;
    private final MeetingEventBroadcaster meetingEventBroadcaster;
    private final AiLiveMeetingRegistry aiLiveMeetingRegistry;
    private final TranscriptService transcriptService;

    public void handle(AiLiveMeetingConnection connection, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.get("type").asText();
            switch (type) {
                case "session.ready" -> handleReady(connection);
                case "session.paused" -> handlePaused(connection);
                case "session.resumed" -> handleResumed(connection);
                case "session.ended" -> handleEnded(connection);
                case "transcript.committed" -> transcriptService.saveCommitted(connection.recordingSessionId(), node);
                case "error" -> handleAiError(connection, payload);
                default -> log.warn("지원하지 않는 AI WebSocket 이벤트입니다. type={}", type);
            }
        } catch (RuntimeException e) {
            log.warn("AI WebSocket 메시지 처리에 실패했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
        }
    }

    public void handleError(AiLiveMeetingConnection connection, Throwable exception) {
        log.warn("AI WebSocket 오류가 발생했습니다. recordingSessionId={}",
                connection.recordingSessionId(), exception);
        connection.close();
        aiLiveMeetingRegistry.remove(connection.recordingSessionId());
    }

    private void handleReady(AiLiveMeetingConnection connection) {
        connection.markReady();
        meetingEventBroadcaster.broadcast(MeetingRealtimeEvent.recording(
                MeetingEventType.RECORDING_STARTED,
                connection.meetingId(),
                connection.recordingSessionId(),
                RecordingSessionStatus.RECORDING
        ));
    }

    private void handlePaused(AiLiveMeetingConnection connection) {
        connection.markPaused();
        meetingEventBroadcaster.broadcast(MeetingRealtimeEvent.recording(
                MeetingEventType.RECORDING_PAUSED,
                connection.meetingId(),
                connection.recordingSessionId(),
                RecordingSessionStatus.PAUSED
        ));
    }

    private void handleResumed(AiLiveMeetingConnection connection) {
        connection.markReady();
        meetingEventBroadcaster.broadcast(MeetingRealtimeEvent.recording(
                MeetingEventType.RECORDING_RESUMED,
                connection.meetingId(),
                connection.recordingSessionId(),
                RecordingSessionStatus.RECORDING
        ));
    }

    private void handleEnded(AiLiveMeetingConnection connection) {
        connection.markEnded();
        meetingEventBroadcaster.broadcastAndComplete(MeetingRealtimeEvent.recording(
                MeetingEventType.MEETING_COMPLETED,
                connection.meetingId(),
                connection.recordingSessionId(),
                RecordingSessionStatus.COMPLETED
        ));
        connection.close();
        aiLiveMeetingRegistry.remove(connection.recordingSessionId());
    }

    private void handleAiError(AiLiveMeetingConnection connection, String payload) {
        log.warn("AI WebSocket error event를 수신했습니다. recordingSessionId={}, payload={}",
                connection.recordingSessionId(), payload);
        connection.close();
        aiLiveMeetingRegistry.remove(connection.recordingSessionId());
    }
}
