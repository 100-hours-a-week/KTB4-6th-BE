package com.backend.meety.domain.recording.event;

import com.backend.meety.domain.recording.realtime.RealtimeFailureReason;

public record RecordingFailedEvent(Long meetingId, Long recordingSessionId, String owner,
                                   RealtimeFailureReason reason) { }
