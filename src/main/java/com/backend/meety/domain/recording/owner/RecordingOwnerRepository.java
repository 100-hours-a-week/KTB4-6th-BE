package com.backend.meety.domain.recording.owner;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingOwnerRepository {

    private final StringRedisTemplate redisTemplate;
    private final RecordingOwnerProperties properties;

    public void save(Long recordingSessionId, String owner) {
        redisTemplate.opsForValue().set(ownerKey(recordingSessionId), owner, properties.ttl());
    }

    static String ownerKey(Long recordingSessionId) {
        return "recording:{" + recordingSessionId + "}:owner";
    }
}
