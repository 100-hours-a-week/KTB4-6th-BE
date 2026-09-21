package com.backend.meety.domain.meeting.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class MeetingSseEmitterFactory {

    public SseEmitter create() {
        return new SseEmitter();
    }
}
