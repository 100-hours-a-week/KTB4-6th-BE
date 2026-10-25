package com.backend.meety.domain.ai.event;

import com.backend.meety.domain.recording.realtime.RealtimeFailureReason;

/** A null request id denotes an accepted transcript's session-scoped data-integrity failure. */
public record AiFatalFailureEvent(Long recordingSessionId, String owner, String sessionStartRequestId,
                                  RealtimeFailureReason reason) { }
