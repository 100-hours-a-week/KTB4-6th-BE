package com.backend.meety.domain.recording.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordingRecoveryMessage(String type, Long recordingSessionId, String code, Long fromSequence) {
    public static RecordingRecoveryMessage recovering(Long id) {
        return new RecordingRecoveryMessage("recording.recovering", id, "AI_CONNECTION_RECOVERING", null);
    }
    public static RecordingRecoveryMessage replay(Long id, long sequence) {
        return new RecordingRecoveryMessage("recording.replay.request", id, null, sequence);
    }
    public static RecordingRecoveryMessage completed(Long id) {
        return new RecordingRecoveryMessage("recording.recovery.completed", id, null, null);
    }
}
