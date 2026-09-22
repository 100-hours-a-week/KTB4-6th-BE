package com.backend.meety.domain.ai.realtime;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AiRequestIdGenerator {

    public String sessionStartRequestId() {
        return "start-" + UUID.randomUUID();
    }

    public String sessionStopRequestId() {
        return "stop-" + UUID.randomUUID();
    }
}
