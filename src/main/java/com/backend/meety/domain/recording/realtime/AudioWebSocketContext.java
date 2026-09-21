package com.backend.meety.domain.recording.realtime;

public record AudioWebSocketContext(
        Long userId,
        Long meetingId,
        Long recordingSessionId
) {
}
