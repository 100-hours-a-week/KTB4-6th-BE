package com.backend.meety.domain.recording.owner;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingOwnerRepository {

    private static final RedisScript<Long> REFRESH_IF_OWNER = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> DELETE_IF_OWNER = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1], KEYS[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RecordingOwnerProperties properties;

    public void save(Long recordingSessionId, String owner) {
        redisTemplate.opsForValue().set(RecordingRedisKeys.owner(recordingSessionId), owner, properties.ttl());
    }

    public boolean refreshIfOwner(Long recordingSessionId, String owner) {
        Long result = redisTemplate.execute(REFRESH_IF_OWNER, List.of(RecordingRedisKeys.owner(recordingSessionId)),
                owner, String.valueOf(properties.ttl().toMillis()));
        return result != null && result > 0;
    }

    public boolean deleteIfOwner(Long recordingSessionId, String owner) {
        Long result = redisTemplate.execute(DELETE_IF_OWNER,
                List.of(RecordingRedisKeys.owner(recordingSessionId), RecordingRedisKeys.sequence(recordingSessionId)),
                owner);
        return result != null && result > 0;
    }
}
