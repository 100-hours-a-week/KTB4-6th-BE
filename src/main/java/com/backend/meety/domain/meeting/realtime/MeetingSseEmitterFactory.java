package com.backend.meety.domain.meeting.realtime;

import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class MeetingSseEmitterFactory {

    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(95).toMillis();

    public SseEmitter create() {
        return new SseEmitter(TIMEOUT_MILLIS);
    }
}
