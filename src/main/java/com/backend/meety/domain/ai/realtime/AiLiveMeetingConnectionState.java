package com.backend.meety.domain.ai.realtime;

public enum AiLiveMeetingConnectionState {

    CONNECTING,
    START_SENT,
    READY,
    PAUSE_SENT,
    PAUSED,
    RESUME_SENT,
    STOP_SENT,
    ENDED,
    CLOSED
}
