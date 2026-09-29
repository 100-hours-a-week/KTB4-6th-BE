package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AudioFormat;

public record AudioWebSocketContext(
        Long userId,
        Long meetingId,
        Long recordingSessionId,
        AudioFormat audioFormat
) {
}
