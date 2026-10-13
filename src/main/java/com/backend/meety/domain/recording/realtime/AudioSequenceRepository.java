package com.backend.meety.domain.recording.realtime;

import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AudioSequenceRepository {

    private final StringRedisTemplate redisTemplate;
    private final AudioSequenceProperties properties;

    public void save(Long recordingSessionId, long lastProcessedSequence) {
        redisTemplate.opsForValue().set(
                sequenceKey(recordingSessionId), String.valueOf(lastProcessedSequence), properties.ttl());
    }

    public OptionalLong find(Long recordingSessionId) {
        String value = redisTemplate.opsForValue().get(sequenceKey(recordingSessionId));
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }

    static String sequenceKey(Long recordingSessionId) {
        return "recording:{" + recordingSessionId + "}:sequence";
    }
}
