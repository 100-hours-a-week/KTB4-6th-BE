package com.backend.meety.domain.recording.owner;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RecordingCommandPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(RecordingCommandMessage message) {
        redisTemplate.convertAndSend(
                RecordingCommandChannels.ofOwner(message.owner()), objectMapper.writeValueAsString(message));
    }
}
