package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.recording.owner.RecordingRedisKeys;
import java.util.OptionalLong;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AudioSequenceRepository {

    private static final DefaultRedisScript<Long> SAVE_ACK = new DefaultRedisScript<>("""
            if ARGV[3] ~= '' and redis.call('GET', KEYS[2]) ~= ARGV[3] then return 0 end
            local previous = redis.call('GET', KEYS[1])
            local value = ARGV[1]
            if previous and (#previous > #value or (#previous == #value and previous > value)) then
                return 0
            end
            redis.call('SET', KEYS[1], value, 'PX', ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AudioSequenceProperties properties;

    public void save(Long recordingSessionId, long lastProcessedSequence) {
        saveIfOwner(recordingSessionId, null, lastProcessedSequence);
    }

    public boolean saveIfOwner(Long recordingSessionId, String owner, long sequence) {
        if (sequence < 0) { throw new IllegalArgumentException("Audio sequence must be nonnegative"); }
        return Long.valueOf(1).equals(redisTemplate.execute(SAVE_ACK,
                List.of(RecordingRedisKeys.sequence(recordingSessionId), RecordingRedisKeys.owner(recordingSessionId)),
                String.valueOf(sequence), String.valueOf(properties.ttl().toMillis()), owner == null ? "" : owner));
    }

    public OptionalLong find(Long recordingSessionId) {
        String value = redisTemplate.opsForValue().get(RecordingRedisKeys.sequence(recordingSessionId));
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }
}
