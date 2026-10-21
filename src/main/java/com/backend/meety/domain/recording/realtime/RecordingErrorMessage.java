package com.backend.meety.domain.recording.realtime;

public record RecordingErrorMessage(String type, Long recordingSessionId, RealtimeFailureReason code,
                                    String message, String action) {
    public static RecordingErrorMessage create(Long id, RealtimeFailureReason reason) {
        return new RecordingErrorMessage("recording.error", id, reason,
                "실시간 전사를 계속할 수 없습니다.", "STOP_RECORDING");
    }
}
