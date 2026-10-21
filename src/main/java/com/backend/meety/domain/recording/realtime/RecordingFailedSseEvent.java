package com.backend.meety.domain.recording.realtime;

public record RecordingFailedSseEvent(String event, Long recordingSessionId, RealtimeFailureReason reason) { }
