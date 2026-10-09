package com.backend.meety.domain.meeting.realtime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis를 거치지 않고 발행한 메시지를 같은 JVM의 구독자에게 바로 넘기는 테스트용 Publisher.
 */
public final class MeetingSseLoopback {

    private static final String CHANNEL = "meeting-sse";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MeetingSseLoopback() {
    }

    public static MeetingSsePublisher publisher(MeetingSseRegistry registry) {
        MeetingSseSubscriber subscriber = new MeetingSseSubscriber(registry, OBJECT_MAPPER);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(eq(CHANNEL), anyString())).thenAnswer(call -> {
            byte[] body = call.<String>getArgument(1).getBytes(StandardCharsets.UTF_8);
            subscriber.onMessage(new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), body), null);
            return 1L;
        });
        return new MeetingSsePublisher(redisTemplate, OBJECT_MAPPER);
    }

    public static JsonNode json(Object payload) {
        return OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(payload));
    }
}
