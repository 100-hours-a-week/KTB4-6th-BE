package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AudioFormat;

public record AudioWebSocketContext(
        Long userId,
        Long meetingId,
        Long recordingSessionId,
        AudioFormat audioFormat,
        long streamEpoch
) {

    public AudioWebSocketContext(Long userId, Long meetingId, Long recordingSessionId, AudioFormat audioFormat) {
        this(userId, meetingId, recordingSessionId, audioFormat, 0L);
    }

    public AudioWebSocketContext withStreamEpoch(long streamEpoch) {
        return new AudioWebSocketContext(userId, meetingId, recordingSessionId, audioFormat, streamEpoch);
    }
}
